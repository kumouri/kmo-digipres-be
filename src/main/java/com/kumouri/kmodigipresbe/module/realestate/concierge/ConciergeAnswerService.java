package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — the <strong>strict, no-hallucination</strong> Anthropic caller that
 * answers a buyer's question using ONLY the retrieved disclosure context, or emits the exact token
 * {@link #HANDOFF_TOKEN} when the answer is not in the context (RE-1 §4 decision 1 / §6.5).
 *
 * <p>A sibling of {@code OfferCopyService} / {@code AnthropicAiAssistService} in shape — per-tenant API
 * key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the call +
 * spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url}, overridden to
 * WireMock in tests). It does <strong>NOT</strong> modify the reused AI core.
 *
 * <p><strong>Why a dedicated service and not {@code AskAiService.ask}:</strong> {@code AskAiService}'s
 * system prompt is <em>soft</em> ("if the context doesn't contain enough information, say so clearly") —
 * fine for an internal CRM assistant but too loose for a buyer-facing "never invent" guarantee. This
 * prompt applies the {@code MultiTradeExtractionStrategy} "do not invent values" discipline to an
 * answer: answer ONLY from the disclosure context; if not clearly supported, reply with EXACTLY
 * {@code HANDOFF} and nothing else; never use general real-estate knowledge; never invent square footage,
 * ages, conditions, or features; never include protected-class / steering language.
 *
 * <p><strong>Error codes are reused, not re-allocated</strong> (RE-1 §4 decision 7): the budget gate owns
 * {@code 1200/1201}, the Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203}. The
 * {@code ListingConciergeService} caller wraps this best-effort and treats any such failure as a
 * {@code HANDOFF} (never a fabricated or empty answer).
 */
@Slf4j
public class ConciergeAnswerService {

    /** The exact single token the model is instructed to emit when the answer is not in the context. */
    public static final String HANDOFF_TOKEN = "HANDOFF";

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a real-estate listing concierge answering a buyer's text message about ONE specific "
            + "property. You will be given DISCLOSURE CONTEXT for that property — facts taken from the "
            + "seller's disclosures. Answer the buyer's question using ONLY facts present in the disclosure "
            + "context. Be concise and friendly, at SMS length (one or two short sentences). If the answer "
            + "is not clearly and directly supported by the disclosure context, do NOT guess, do NOT use "
            + "general real-estate knowledge, and do NOT make assumptions — instead reply with EXACTLY the "
            + "single token HANDOFF and nothing else (no punctuation, no other words). Never invent square "
            + "footage, ages, prices, conditions, repairs, or features that are not in the context. Never "
            + "include protected-class references or steering language. Output ONLY the SMS answer text, or "
            + "the single token HANDOFF.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String conciergeModel;
    private final String systemPromptOverride;

    public ConciergeAnswerService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.realestate.concierge-model:claude-haiku-4-5}") String conciergeModel,
            @Value("${kmosf.realestate.concierge-system-prompt:}") String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.conciergeModel = conciergeModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * Asks the model the buyer's question against the disclosure context. Returns the trimmed answer text
     * (which may be exactly {@link #HANDOFF_TOKEN}). Errors (1200/1202/1203) propagate so the caller can
     * treat them as a handoff (best-effort — never a fabricated answer).
     *
     * <p>The caller is responsible for the no-chunks short-circuit (RE-1 §6.5 step 3) — this method is
     * never called with a blank context.
     */
    public Mono<String> answer(String disclosureContext, String question) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, disclosureContext, question))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(result.text.trim()));
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

    private Mono<CompletionResult> postMessages(String apiKey, String context, String question) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", conciergeModel);
        body.put("max_tokens", 256);
        body.put("system", systemPrompt());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", buildUserPrompt(context, question))));
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
                                "Anthropic concierge-answer call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .flatMap(ConciergeAnswerService::parseResponse);
    }

    private static Mono<CompletionResult> parseResponse(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.path("content").isArray()) {
            for (JsonNode block : node.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        String text = sb.toString();
        if (text.isBlank()) {
            // A blank model answer is surfaced as 1202 so the caller hands off rather than texting an
            // empty body — it never becomes a fabricated answer.
            return Mono.error(new DigiPresBeException(
                    "Anthropic concierge-answer returned an empty answer", 1202, 502));
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return Mono.just(new CompletionResult(text, inputTokens, outputTokens));
    }

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    private static String buildUserPrompt(String context, String question) {
        return "DISCLOSURE CONTEXT for this property:\n"
                + context
                + "\n\nBuyer's question: " + question
                + "\n\nAnswer using ONLY the disclosure context above. If it is not clearly supported, "
                + "reply with EXACTLY: HANDOFF";
    }

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = conciergeModel != null && conciergeModel.toLowerCase().contains("haiku");
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
