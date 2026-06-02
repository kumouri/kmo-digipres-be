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
 * Extracts structured lead fields from a voicemail transcript via the Anthropic Messages API
 * (Phase 1 — NMM voicemail-to-lead). A <strong>new additive service that mirrors
 * {@link com.kumouri.kmodigipresbe.service.ai.AnthropicAiAssistService} exactly</strong> in
 * shape — per-tenant API key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey}
 * with a {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate
 * BEFORE the call + spend record AFTER, and a configurable base-url
 * ({@code kmosf.ai.anthropic.base-url}, default the real Anthropic endpoint, OVERRIDDEN to
 * WireMock in every test — §7).
 *
 * <p>It does <strong>NOT</strong> modify {@code AnthropicAiAssistService} (that reused core
 * stays empty-diff vs {@code main}); this is a sibling caller with its own strict
 * voicemail-extraction system prompt that asks the model to return ONLY JSON
 * {@code {name, phone, address, problem, urgency, callbackRequested}}. Parsing is
 * <strong>defensive</strong>: a missing/blank field, fenced JSON, or a non-200/parse failure
 * degrades to {@link VoicemailExtraction#empty()} rather than failing the ingest — AI is
 * triage, not truth (plan §8), and the raw transcript + recording are always attached so Rob
 * can verify.
 *
 * <p>Error codes are reused, not re-allocated: the budget gate owns {@code 1200/1201}, the
 * Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203} (all from the AI
 * range, surfaced by {@code AiUsageRecorder} / the same call shape as the exemplar). The
 * voicemail pipeline calls this <em>best-effort</em> (a budget-exhausted {@code 1200} or
 * upstream {@code 1202} is caught upstream and the lead is still created from the raw
 * transcript) so an AI outage never drops a lead.
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

    private static final String SYSTEM_PROMPT =
            "You extract structured lead details from the transcript of a voicemail left for a "
            + "pest-control / mole-removal business. Respond with ONLY a single minified JSON "
            + "object and nothing else — no prose, no markdown, no code fences. The object MUST "
            + "have exactly these keys: \"name\" (the caller's name, or null), \"phone\" (a "
            + "callback number stated in the message, or null), \"address\" (the service "
            + "address, or null), \"problem\" (a short description of the pest/mole problem, or "
            + "null), \"urgency\" (the caller's stated urgency such as \"high\"/\"this week\", or "
            + "null), and \"callbackRequested\" (boolean true if the caller asked to be called "
            + "back, else false). Use null for any field not present in the transcript. Do not "
            + "invent values.";

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String extractionModel;

    public VoicemailExtractionService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.voicemail.extraction-model:claude-haiku-4-5}") String extractionModel) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.extractionModel = extractionModel;
    }

    /**
     * Extracts structured fields from the transcript. Mirrors
     * {@code AnthropicAiAssistService.call}: tenant key → budget gate → POST → record spend.
     * A blank transcript short-circuits to {@link VoicemailExtraction#empty()} without spending.
     */
    public Mono<VoicemailExtraction> extract(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return Mono.just(VoicemailExtraction.empty());
        }
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, transcript))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(parseExtraction(result.text)));
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

    private Mono<CompletionResult> postMessages(String apiKey, String transcript) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", extractionModel);
        body.put("max_tokens", 512);
        body.put("system", SYSTEM_PROMPT);
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
     * Parses the model's JSON answer into a {@link VoicemailExtraction}. Tolerant of code
     * fences and surrounding prose (strips to the first {@code {...}} block); any parse failure
     * degrades to {@link VoicemailExtraction#empty()} (logged) — never throws.
     */
    private VoicemailExtraction parseExtraction(String text) {
        if (text == null || text.isBlank()) {
            return VoicemailExtraction.empty();
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("Voicemail extraction: model response was not JSON; using empty extraction");
            return VoicemailExtraction.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new VoicemailExtraction(
                    textOrNull(node, "name"),
                    textOrNull(node, "phone"),
                    textOrNull(node, "address"),
                    textOrNull(node, "problem"),
                    textOrNull(node, "urgency"),
                    node.path("callbackRequested").asBoolean(false));
        } catch (Exception ex) {
            log.debug("Voicemail extraction: JSON parse failed ({}); using empty extraction",
                    ex.getMessage());
            return VoicemailExtraction.empty();
        }
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
        boolean haiku = extractionModel != null && extractionModel.toLowerCase().contains("haiku");
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
