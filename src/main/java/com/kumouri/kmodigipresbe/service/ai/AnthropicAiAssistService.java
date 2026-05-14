package com.kumouri.kmodigipresbe.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Messages API client. Per-tenant API key from
 * {@code IntegrationConnection(provider="anthropic").secrets.apiKey}; falls
 * back to {@code kmosf.ai.anthropic.house-key} when the tenant has not
 * connected its own account.
 *
 * <p>Model picker: Haiku for summarize (latency/cost win), Sonnet for
 * draftReply (quality matters more for outbound prose). Override via
 * {@code kmosf.ai.anthropic.summarize-model} / {@code .draft-model}.
 *
 * <p>Budget enforcement happens via {@link AiUsageRecorder#checkBudget} BEFORE
 * the call and {@link AiUsageRecorder#record} AFTER. The recorder owns error
 * code 1200; this class adds 1202 for upstream non-200, 1203 for missing key.
 */
@Slf4j
@Service
public class AnthropicAiAssistService implements AiAssistService {

    public static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    /** Rough cost estimate (USD per 1M tokens). Update via config when prices change. */
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String summarizeModel;
    private final String draftModel;

    public AnthropicAiAssistService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.ai.anthropic.summarize-model:claude-haiku-4-5}") String summarizeModel,
            @Value("${kmosf.ai.anthropic.draft-model:claude-sonnet-4-6}") String draftModel) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.summarizeModel = summarizeModel;
        this.draftModel = draftModel;
    }

    @Override
    public Mono<AiSummary> summarizeTimeline(SummarizeTimelineRequest req) {
        String prompt = buildSummarizePrompt(req);
        return call(summarizeModel, prompt,
                "You are an assistant that summarizes a contact's recent CRM activity. "
                        + "Produce one short paragraph (3-5 sentences) noting recent activity, "
                        + "open questions, and recommended next steps.")
                .map(r -> new AiSummary(r.text, r.inputTokens, r.outputTokens));
    }

    @Override
    public Mono<AiDraft> draftReply(DraftReplyRequest req) {
        String prompt = buildDraftPrompt(req);
        return call(draftModel, prompt,
                "You are an assistant that drafts a courteous, professional reply to an inbound "
                        + "email thread. Match the existing tone. Keep it concise (1-3 paragraphs).")
                .map(r -> new AiDraft(r.text, r.inputTokens, r.outputTokens));
    }

    private Mono<CompletionResult> call(String model, String prompt, String system) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(model, key, system, prompt))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(model, result))
                        .thenReturn(result));
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

    private Mono<CompletionResult> postMessages(String model, String apiKey, String system, String prompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", 1024);
        body.put("system", system);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", prompt)));
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
                                "Anthropic call failed: " + response.statusCode() + " " + errBody,
                                1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(AnthropicAiAssistService::parseResponse);
    }

    private static CompletionResult parseResponse(JsonNode node) {
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

    private BigDecimal estimateUsd(String model, CompletionResult result) {
        BigDecimal input = pricePer(model, true).multiply(BigDecimal.valueOf(result.inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = pricePer(model, false).multiply(BigDecimal.valueOf(result.outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return input.add(output);
    }

    private static BigDecimal pricePer(String model, boolean isInput) {
        // The exact pricing depends on the model + region; this is a rough estimate
        // that's good enough for budget gating. Tenants who exceed should be alerted
        // via the cap, not this rough estimate.
        if (model != null && model.toLowerCase().contains("haiku")) {
            return isInput ? HAIKU_INPUT_PER_MILLION : HAIKU_OUTPUT_PER_MILLION;
        }
        return isInput ? SONNET_INPUT_PER_MILLION : SONNET_OUTPUT_PER_MILLION;
    }

    private static String buildSummarizePrompt(SummarizeTimelineRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("Contact id: ").append(req.contactId()).append("\n\n");
        sb.append("Timeline (most recent first):\n");
        List<AiAssistService.TimelineEntry> entries = req.timeline() == null ? List.of() : req.timeline();
        for (AiAssistService.TimelineEntry e : entries) {
            sb.append("- [").append(e.type()).append("] ").append(e.at()).append(": ")
                    .append(e.summary() == null ? "" : e.summary());
            if (e.body() != null && !e.body().isBlank()) sb.append(" — ").append(e.body());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String buildDraftPrompt(DraftReplyRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thread subject: ").append(req.threadSubject() == null ? "" : req.threadSubject()).append("\n\n");
        if (req.intent() != null && !req.intent().isBlank()) {
            sb.append("Reply intent: ").append(req.intent()).append("\n\n");
        }
        sb.append("Recent messages in the thread:\n");
        List<ThreadMessage> msgs = req.recentMessages() == null ? List.of() : new ArrayList<>(req.recentMessages());
        for (ThreadMessage m : msgs) {
            sb.append("From ").append(m.from()).append(" at ").append(m.at()).append(":\n");
            sb.append(m.body() == null ? "" : m.body()).append("\n---\n");
        }
        sb.append("\nDraft the reply now.");
        return sb.toString();
    }

    private record CompletionResult(String text, long inputTokens, long outputTokens) {
    }
}
