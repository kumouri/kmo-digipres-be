package com.kumouri.kmodigipresbe.integration.molevision;

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
 * Classifies a homeowner's pest photo (mole / vole / gopher / none / unsure + confidence) via the
 * Anthropic Messages <strong>vision</strong> API (Phase 2 — NMM "is this a mole?" photo triage,
 * Feature B). A <strong>new additive service that mirrors
 * {@link com.kumouri.kmodigipresbe.service.ai.AnthropicAiAssistService} and the Phase-1
 * {@code VoicemailExtractionService} exactly</strong> in shape — per-tenant API key from
 * {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE
 * the call + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url},
 * default the real Anthropic endpoint, OVERRIDDEN to WireMock in every test — §7).
 *
 * <p>The only structural difference from the voicemail extractor is the request body: instead of a
 * text-only user message, this sends a user message with <strong>two content blocks</strong> — an
 * {@code image} block ({@code {"type":"image","source":{"type":"base64","media_type":"<mt>",
 * "data":"<b64>"}}}) carrying the base64-encoded photo, followed by a {@code text} block with the
 * classify instruction. The strict system prompt asks the model to return ONLY JSON
 * {@code {classification: "mole"|"vole"|"gopher"|"none"|"unsure", confidence: 0.0-1.0,
 * rationale: string}}.
 *
 * <p>It does <strong>NOT</strong> modify {@code AnthropicAiAssistService} (that reused core stays
 * empty-diff vs {@code main}); this is a sibling caller. Parsing is <strong>defensive</strong>: a
 * blank/fenced/prose-wrapped or non-200/parse-failure answer degrades to
 * {@link MoleClassification#unsure()} rather than throwing — AI is triage, not truth (plan §8),
 * and the photo is always stored as an {@code Attachment} so Rob can verify.
 *
 * <p><strong>Blocking work off the Netty loop (§9 / Safety rules):</strong> the base64 encode is
 * CPU-bound over a potentially multi-MB image, so it runs on {@link Schedulers#boundedElastic()}
 * via {@code Mono.fromCallable(...).subscribeOn(...)} — never the event loop.
 *
 * <p>Error codes are reused, not re-allocated: the budget gate owns {@code 1200/1201}, the
 * Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203} (all from the AI range,
 * the same call shape as the exemplar). The triage pipeline calls this <em>best-effort</em> (a
 * budget-exhausted {@code 1200} or upstream {@code 1202} degrades to {@code UNSURE}) so an AI
 * outage never drops the lead.
 */
@Slf4j
@Service
public class MoleVisionService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String SYSTEM_PROMPT =
            "You are a pest-identification assistant for a mole-removal business. You are shown ONE "
            + "photo a homeowner took of their lawn or yard. Decide whether it shows a mole mound, "
            + "a vole sign, a gopher mound, or no pest sign at all. A mole mound is a conical "
            + "volcano-shaped hill of loose soil with no visible entry hole; a gopher mound is "
            + "fan- or crescent-shaped with a visible plugged hole off to one side; voles leave "
            + "narrow surface runways and small holes rather than mounds. Respond with ONLY a "
            + "single minified JSON object and nothing else — no prose, no markdown, no code "
            + "fences. The object MUST have exactly these keys: \"classification\" (one of "
            + "\"mole\", \"vole\", \"gopher\", \"none\", or \"unsure\"), \"confidence\" (a number "
            + "from 0.0 to 1.0 for how confident you are), and \"rationale\" (a short one-sentence "
            + "explanation). Use \"unsure\" with a low confidence when the photo is unclear, "
            + "out of focus, or does not clearly show any of these. Do not guess wildly; this is a "
            + "triage hint a human will confirm.";

    private static final String USER_TEXT =
            "Classify this homeowner photo. Return ONLY the JSON object described in the system "
            + "prompt.";

    /** Anthropic vision accepts these media types; anything else is rejected upstream (4012). */
    private static final List<String> SUPPORTED_MEDIA_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String visionModel;

    public MoleVisionService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.mole-triage.vision-model:claude-sonnet-4-6}") String visionModel) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.visionModel = visionModel;
    }

    /** True iff {@code mediaType} is an Anthropic-vision-supported image type. */
    public static boolean isSupportedMediaType(String mediaType) {
        return mediaType != null && SUPPORTED_MEDIA_TYPES.contains(mediaType.trim().toLowerCase());
    }

    /**
     * Classifies the image bytes. Mirrors {@code AnthropicAiAssistService.call} /
     * {@code VoicemailExtractionService.extract}: tenant key → budget gate → POST (with the image
     * content block) → record spend → defensive parse. Empty/blank input short-circuits to
     * {@link MoleClassification#unsure()} without spending. The base64 encode is on
     * {@code boundedElastic} (never the Netty loop).
     */
    public Mono<MoleClassification> classify(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.just(MoleClassification.unsure());
        }
        String effectiveMediaType = isSupportedMediaType(mediaType)
                ? mediaType.trim().toLowerCase()
                : "image/jpeg";
        return encodeBase64(imageBytes)
                .flatMap(b64 -> TenantContextHolder.required()
                        .flatMap(ctx -> resolveKey(ctx.tenantId()))
                        .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                        .flatMap(key -> postMessages(key, b64, effectiveMediaType))
                        .flatMap(result -> usageRecorder.record(
                                        result.inputTokens, result.outputTokens, estimateUsd(result))
                                .thenReturn(parseClassification(result.text))));
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

    private Mono<CompletionResult> postMessages(String apiKey, String base64Image, String mediaType) {
        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", mediaType,
                        "data", base64Image));
        Map<String, Object> textBlock = Map.of(
                "type", "text",
                "text", USER_TEXT);
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", List.of(imageBlock, textBlock));

        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("max_tokens", 512);
        body.put("system", SYSTEM_PROMPT);
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
                                "Anthropic mole-vision call failed: "
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
     * Parses the model's JSON answer into a {@link MoleClassification}. Tolerant of code fences and
     * surrounding prose (strips to the first {@code {...}} block); any parse failure or an
     * unrecognized label degrades to {@link MoleClassification#unsure()} (logged) — never throws.
     */
    private MoleClassification parseClassification(String text) {
        if (text == null || text.isBlank()) {
            return MoleClassification.unsure();
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("Mole vision: model response was not JSON; using UNSURE");
            return MoleClassification.unsure();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            MoleClassificationCategory category =
                    MoleClassificationCategory.fromWire(textOrNull(node, "classification"));
            double confidence = clampConfidence(node.path("confidence").asDouble(0.0));
            String rationale = textOrNull(node, "rationale");
            return new MoleClassification(category, confidence, rationale);
        } catch (Exception ex) {
            log.debug("Mole vision: JSON parse failed ({}); using UNSURE", ex.getMessage());
            return MoleClassification.unsure();
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

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = visionModel != null && visionModel.toLowerCase().contains("haiku");
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
