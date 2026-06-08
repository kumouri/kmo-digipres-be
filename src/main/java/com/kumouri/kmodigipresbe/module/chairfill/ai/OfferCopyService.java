package com.kumouri.kmodigipresbe.module.chairfill.ai;

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
 * ChairFill CF-3 — drafts a short, on-brand, <strong>Claude-personalized, time-boxed</strong> gap-fill
 * SMS <em>offer</em> for a freed salon slot. A new additive service that mirrors
 * {@link com.kumouri.kmodigipresbe.module.chairfill.ai.ReminderCopyService} /
 * {@link com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService} /
 * {@link com.kumouri.kmodigipresbe.service.ai.AnthropicAiAssistService} exactly in shape — per-tenant
 * API key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the call
 * + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url}, default the
 * real Anthropic endpoint, OVERRIDDEN to WireMock in tests).
 *
 * <p>It does <strong>NOT</strong> modify the reused AI core; this is a sibling caller. The system prompt
 * asks for a warm, concise SMS that names the stylist + the slot time, makes the time-box explicit (reply
 * YES within the window), never invents facts, and outputs ONLY the SMS body. Configurable via
 * {@code kmosf.chairfill.offer-system-prompt} (blank = the built-in default).
 *
 * <p><strong>Error codes are reused, not re-allocated:</strong> the budget gate owns {@code 1200/1201},
 * the Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203}. The {@code GapFillService}
 * caller wraps this <em>best-effort</em> ({@code onErrorResume}): a budget-exhausted {@code 1200}, an
 * upstream {@code 1202}, or a missing-key {@code 1203} all degrade to a deterministic generic offer — the
 * personalization is a nicety, never a blocker (plan §4 AI-budget mitigation). A blank/whitespace model
 * answer surfaces {@code 1202} so the caller falls back rather than texting an empty body.
 */
@Slf4j
public class OfferCopyService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You write a single short SMS offering a salon client a chair-opening that just came free, "
            + "on behalf of the salon owner. Write in a warm, friendly, concise voice — like a real "
            + "stylist who knows the client, not a corporate brand. Greet the client by first name when "
            + "one is given, name the stylist and the time of the freed slot when given, and make the "
            + "offer clearly TIME-BOXED: tell them to reply YES within the given window to claim it, and "
            + "that it goes to the next person otherwise. Keep it to ONE or TWO short sentences that fit "
            + "in a text message. Do NOT invent facts, prices, discounts, names, or details not provided. "
            + "Do NOT include placeholders, links, signatures, emoji, or markdown — output ONLY the SMS "
            + "text itself, nothing else.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String draftModel;
    private final String systemPromptOverride;

    public OfferCopyService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.chairfill.offer-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.chairfill.offer-system-prompt:}") String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.draftModel = draftModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * The personalization inputs the drafter weaves into the offer. All optional/nullable except the
     * window phrase — the prompt instructs the model to skip anything not provided rather than invent it.
     *
     * @param clientFirstName the client's first name (greeting), or null
     * @param stylistName     the freed slot's stylist display name, or null
     * @param serviceName     the service for the freed slot, or null
     * @param slotWhen        a human phrase for the freed slot time (e.g. "today at 2:30 PM"), or null
     * @param replyWindow     a human phrase for the time-box (e.g. "the next 10 minutes"); never null
     * @param brandTone       the owner's brand-voice hint, or null
     */
    public record OfferContext(
            String clientFirstName,
            String stylistName,
            String serviceName,
            String slotWhen,
            String replyWindow,
            String brandTone) {
    }

    /**
     * Drafts the offer SMS. Mirrors {@code ReminderCopyService.draftReminder}: tenant key → budget gate
     * → POST → record spend → return the trimmed SMS text. Errors (1200/1202/1203) propagate so the
     * caller can fall back to a generic template (best-effort).
     */
    public Mono<String> draftOffer(OfferContext context) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, context))
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

    private Mono<CompletionResult> postMessages(String apiKey, OfferContext context) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", draftModel);
        body.put("max_tokens", 256);
        body.put("system", systemPrompt());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", buildUserPrompt(context))));
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
                                "Anthropic offer-draft call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .flatMap(this::parseAnthropicResponse);
    }

    private Mono<CompletionResult> parseAnthropicResponse(JsonNode node) {
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
            return Mono.error(new DigiPresBeException(
                    "Anthropic offer-draft returned an empty offer", 1202, 502));
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return Mono.just(new CompletionResult(text, inputTokens, outputTokens));
    }

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    private static String buildUserPrompt(OfferContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write a friendly, TIME-BOXED gap-fill offer text for a chair-opening that just "
                + "came free.\n\n");
        sb.append("Client first name: ").append(blankToUnknown(c.clientFirstName())).append('\n');
        sb.append("Stylist: ").append(blankToUnknown(c.stylistName())).append('\n');
        sb.append("Service for the freed slot: ").append(blankToUnknown(c.serviceName())).append('\n');
        sb.append("Freed slot time: ").append(blankToUnknown(c.slotWhen())).append('\n');
        sb.append("Reply window (they must reply YES within this to claim it): ")
                .append(blankToUnknown(c.replyWindow())).append('\n');
        if (c.brandTone() != null && !c.brandTone().isBlank()) {
            sb.append("Salon brand voice / tone to match: ").append(c.brandTone().trim()).append('\n');
        }
        sb.append("\nWrite the SMS now.");
        return sb.toString();
    }

    private static String blankToUnknown(String v) {
        return (v == null || v.isBlank()) ? "(not provided)" : v.trim();
    }

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = draftModel != null && draftModel.toLowerCase().contains("haiku");
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
