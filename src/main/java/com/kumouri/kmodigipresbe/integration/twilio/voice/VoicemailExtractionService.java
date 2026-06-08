package com.kumouri.kmodigipresbe.integration.twilio.voice;

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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <strong>reusable</strong> Anthropic Messages <strong>text</strong> transport for voicemail
 * extraction (HS-1 — Home Services front desk), generalized from the mole-specific Phase-1
 * extractor so every vertical can share one extract core. Exactly the
 * {@link com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService} relationship, one layer up:
 * the per-vertical {@code …ExtractionStrategy} classes (mole, multi-trade) are the thin callers and
 * this owns the transport.
 *
 * <p>Same shape as {@code AiVisionService} / {@code AnthropicAiAssistService}: per-tenant API key
 * from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the
 * call + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url},
 * default the real Anthropic endpoint, OVERRIDDEN to WireMock in every test — §7).
 *
 * <p>Unlike the per-vertical callers, the {@code model} and {@code systemPrompt} are
 * <strong>method parameters</strong> of {@link #extractRaw}: each strategy owns its own model +
 * prompt config (there is no model {@code @Value} here). The user message wraps the transcript with
 * the fixed {@code "Voicemail transcript:\n\n"} prefix — identical for every vertical, so the wire
 * request stays <strong>byte-identical</strong> for the mole caller (model {@code claude-haiku-4-5},
 * {@code max_tokens} 512, system = the mole prompt) and the Phase-1 NMM IT WireMock stub +
 * {@code verify(1, ...)} are unchanged.
 *
 * <p>{@link #extractRaw} returns the model's <strong>raw parsed JSON</strong> ({@link JsonNode},
 * open schema), exactly the {@code AiVisionService.extract} return shape — each strategy maps it
 * into its own record. Parsing is <strong>defensive</strong>: a blank transcript short-circuits to
 * an empty {@code ObjectNode} without spending; a blank/fenced/prose-wrapped or non-200/parse-failure
 * answer degrades to an empty {@code ObjectNode} rather than throwing — AI is triage, not truth
 * (plan §8), and the raw transcript + recording are always attached so a human can verify.
 *
 * <p>Error codes are reused, not re-allocated: the budget gate owns {@code 1200/1201}, the
 * Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203} (all from the AI range).
 * The voicemail pipeline calls the strategies <em>best-effort</em> (a budget-exhausted {@code 1200}
 * or upstream {@code 1202} is caught upstream and the lead is still created from the raw transcript)
 * so an AI outage never drops a lead.
 */
@Slf4j
@Service
public class VoicemailExtractionService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;

    public VoicemailExtractionService(
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

    /**
     * Runs the shared extract transport for the given {@code model} + {@code systemPrompt} and
     * returns the model's raw parsed JSON ({@link JsonNode}). Mirrors
     * {@code AiVisionService.extract}: tenant key → budget gate → POST → record spend → defensive
     * parse. A blank transcript short-circuits to an empty {@code ObjectNode} without spending (and
     * without requiring a {@code TenantContext}); a blank/non-JSON/parse-failure answer also
     * degrades to an empty {@code ObjectNode} (never throws).
     *
     * @param transcript   the voicemail transcript (blank → empty object, no spend)
     * @param model        the Anthropic model id the calling strategy chose
     * @param systemPrompt the calling strategy's system prompt
     */
    public Mono<JsonNode> extractRaw(String transcript, String model, String systemPrompt) {
        if (transcript == null || transcript.isBlank()) {
            return Mono.just(objectMapper.createObjectNode());
        }
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, transcript, model, systemPrompt))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(model, result))
                        .thenReturn(parseJson(result.text)));
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

    private Mono<CompletionResult> postMessages(String apiKey, String transcript, String model,
                                                String systemPrompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", 512);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Voicemail transcript:\n\n" + transcript)));
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
                                "Anthropic voicemail-extraction call failed: "
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
            log.debug("Voicemail extraction: model response was not JSON; using empty object");
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isMissingNode() ? objectMapper.createObjectNode() : node;
        } catch (Exception ex) {
            log.debug("Voicemail extraction: JSON parse failed ({}); using empty object",
                    ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** Returns the substring from the first '{' to the last '}', or null if absent. */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) return null;
        return text.substring(start, end + 1);
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
