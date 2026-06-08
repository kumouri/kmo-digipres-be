package com.kumouri.kmodigipresbe.service.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <strong>reusable</strong> Anthropic Messages <strong>vision</strong> transport, extracted
 * from the mole-specific {@code MoleVisionService} so every vertical can share one image-classify
 * core. Same shape as the Phase-1 {@code VoicemailExtractionService} / {@code MoleVisionService}:
 * per-tenant API key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the
 * call + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url},
 * default the real Anthropic endpoint, OVERRIDDEN to WireMock in tests).
 *
 * <p>The user message carries <strong>two content blocks</strong> — an {@code image} block
 * ({@code {"type":"image","source":{"type":"base64","media_type":"<mt>","data":"<b64>"}}}) FIRST,
 * then a {@code text} block with the caller's instruction. Unlike the per-vertical callers, the
 * {@code model}, {@code systemPrompt}, and {@code userText} are <strong>method parameters</strong>:
 * each caller owns its own model + prompt config (there is no model {@code @Value} here).
 *
 * <p>Two result shapes are offered, both <strong>defensive</strong> (never throw on a bad answer —
 * AI is triage, not truth):
 * <ul>
 *   <li>{@link #classify} → a {@link VisionClassification} ({@code classification}/{@code confidence}/
 *       {@code rationale}); a blank/non-JSON/parse-failure answer degrades to
 *       {@link VisionClassification#unsure()};</li>
 *   <li>{@link #extract} → the raw parsed {@link JsonNode} (open schema); a blank/non-JSON/parse-failure
 *       answer degrades to an empty {@code ObjectNode}.</li>
 * </ul>
 *
 * <p><strong>Blocking work off the Netty loop:</strong> the base64 encode is CPU-bound over a
 * potentially multi-MB image, so it runs on {@link Schedulers#boundedElastic()} via
 * {@code Mono.fromCallable(...).subscribeOn(...)} — never the event loop.
 *
 * <p>Error codes are reused, not re-allocated: the budget gate owns {@code 1200/1201}, the
 * Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203} (all from the AI range).
 */
@Slf4j
@Service
public class AiVisionService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    /** Anthropic vision accepts these media types; anything else is rejected upstream (4012). */
    private static final List<String> SUPPORTED_MEDIA_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;

    public AiVisionService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
    }

    /** True iff {@code mediaType} is an Anthropic-vision-supported image type. */
    public static boolean isSupportedMediaType(String mediaType) {
        return mediaType != null && SUPPORTED_MEDIA_TYPES.contains(mediaType.trim().toLowerCase());
    }

    /**
     * Classifies the image bytes into a {@link VisionClassification} ({@code classification}/
     * {@code confidence}/{@code rationale}). Tenant key → budget gate → POST (image block first) →
     * record spend → defensive parse. Empty/blank input short-circuits to
     * {@link VisionClassification#unsure()} without spending. The base64 encode is on
     * {@code boundedElastic} (never the Netty loop).
     */
    public Mono<VisionClassification> classify(byte[] imageBytes, String mediaType, String model,
                                               String systemPrompt, String userText) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.just(VisionClassification.unsure());
        }
        return encodeBase64(imageBytes)
                .flatMap(b64 -> TenantContextHolder.required()
                        .flatMap(ctx -> resolveKey(ctx.tenantId()))
                        .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                        .flatMap(key -> postMessages(key, b64, mediaType, model, systemPrompt, userText))
                        .flatMap(result -> usageRecorder.record(
                                        result.inputTokens, result.outputTokens, estimateUsd(model, result))
                                .thenReturn(parseClassification(result.text))));
    }

    /**
     * Runs the same vision transport but returns the model's <strong>raw parsed JSON</strong>
     * ({@link JsonNode}) for open-schema extractions (the {@code VoicemailExtractionService} shape,
     * image-bearing). Empty/blank input short-circuits to an empty {@code ObjectNode} without
     * spending; a blank/non-JSON/parse-failure answer also degrades to an empty {@code ObjectNode}
     * (never throws).
     */
    public Mono<JsonNode> extract(byte[] imageBytes, String mediaType, String model,
                                  String systemPrompt, String userText) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.just(objectMapper.createObjectNode());
        }
        return encodeBase64(imageBytes)
                .flatMap(b64 -> TenantContextHolder.required()
                        .flatMap(ctx -> resolveKey(ctx.tenantId()))
                        .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                        .flatMap(key -> postMessages(key, b64, mediaType, model, systemPrompt, userText))
                        .flatMap(result -> usageRecorder.record(
                                        result.inputTokens, result.outputTokens, estimateUsd(model, result))
                                .thenReturn(parseJson(result.text))));
    }

    /** Base64-encodes the image off the Netty event loop (CPU-bound over a multi-MB photo). */
    private Mono<String> encodeBase64(byte[] imageBytes) {
        return Mono.fromCallable(() -> Base64.getEncoder().encodeToString(imageBytes))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> resolveKey(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .map(IntegrationConnection::getSecrets)
                .mapNotNull(secrets -> secrets == null ? null : secrets.get("apiKey"))
                .filter(k -> k != null && !k.isBlank())
                .switchIfEmpty(Mono.defer(() -> houseKey.isBlank()
                        ? Mono.error(new DigiPresBeException(
                                "No Anthropic API key configured for tenant and no house key set",
                                1203, 412))
                        : Mono.just(houseKey)));
    }

    private Mono<CompletionResult> postMessages(String apiKey, String base64Image, String mediaType,
                                                String model, String systemPrompt, String userText) {
        // Normalize the media type exactly as the original MoleVisionService did — trim + lowercase
        // for a supported type, else fall back to image/jpeg — so the wire request stays
        // byte-identical for the mole caller and well-behaved for any new caller.
        String effectiveMediaType = isSupportedMediaType(mediaType)
                ? mediaType.trim().toLowerCase()
                : "image/jpeg";
        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", effectiveMediaType,
                        "data", base64Image));
        Map<String, Object> textBlock = Map.of(
                "type", "text",
                "text", userText);
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(imageBlock, textBlock));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", 512);
        body.put("system", systemPrompt);
        body.put("messages", List.of(userMessage));

        return http.post()
                .uri("")
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(errBody -> Mono.error(new DigiPresBeException(
                                "Anthropic vision call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(this::parseAnthropicResponse);
    }

    private CompletionResult parseAnthropicResponse(JsonNode node) {
        String text = "";
        if (node.path("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : node.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            text = sb.toString();
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return new CompletionResult(text, inputTokens, outputTokens);
    }

    /**
     * Parses the model's JSON answer into a {@link VisionClassification}. Tolerant of code fences
     * and surrounding prose (strips to the first {@code {...}} block); reads {@code classification}
     * (the label), {@code confidence} (clamped to {@code [0,1]}, default 0.0), and {@code rationale}.
     * Any blank/non-JSON/parse failure degrades to {@link VisionClassification#unsure()} (logged) —
     * never throws.
     */
    private VisionClassification parseClassification(String text) {
        if (text == null || text.isBlank()) {
            return VisionClassification.unsure();
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("AI vision: model response was not JSON; using unsure");
            return VisionClassification.unsure();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String label = textOrNull(node, "classification");
            double confidence = clampConfidence(node.path("confidence").asDouble(0.0));
            String rationale = textOrNull(node, "rationale");
            return new VisionClassification(label, confidence, rationale);
        } catch (Exception ex) {
            log.debug("AI vision: JSON parse failed ({}); using unsure", ex.getMessage());
            return VisionClassification.unsure();
        }
    }

    /**
     * Parses the model's answer into a raw {@link JsonNode} (open schema). Tolerant of code fences
     * and surrounding prose (strips to the first {@code {...}} block); any blank/non-JSON/parse
     * failure degrades to an empty {@code ObjectNode} (logged) — never throws.
     */
    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("AI vision: extract response was not JSON; using empty object");
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isMissingNode() ? objectMapper.createObjectNode() : node;
        } catch (Exception ex) {
            log.debug("AI vision: extract JSON parse failed ({}); using empty object", ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private static double clampConfidence(double raw) {
        if (Double.isNaN(raw) || raw < 0.0) return 0.0;
        return Math.min(raw, 1.0);
    }

    /** Returns the substring from the first '{' to the last '}', or null if absent. */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) return null;
        return text.substring(start, end + 1);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private BigDecimal estimateUsd(String model, CompletionResult result) {
        boolean haiku = model != null && model.toLowerCase().contains("haiku");
        BigDecimal inPer = haiku ? HAIKU_INPUT_PER_MILLION : SONNET_INPUT_PER_MILLION;
        BigDecimal outPer = haiku ? HAIKU_OUTPUT_PER_MILLION : SONNET_OUTPUT_PER_MILLION;
        BigDecimal input = inPer.multiply(BigDecimal.valueOf(result.inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = outPer.multiply(BigDecimal.valueOf(result.outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return input.add(output);
    }

    private record CompletionResult(String text, long inputTokens, long outputTokens) {
    }
}
