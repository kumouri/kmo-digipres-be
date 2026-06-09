package com.kumouri.kmodigipresbe.module.ar;

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
 * "Get Paid" AR-3 — drafts a short, on-brand, <strong>Claude-personalized</strong> dunning (overdue
 * payment) reminder SMS for an overdue {@link com.kumouri.kmodigipresbe.model.billing.Invoice}. A
 * <strong>new additive service that mirrors {@link com.kumouri.kmodigipresbe.module.chairfill.ai.ReminderCopyService}
 * / {@link com.kumouri.kmodigipresbe.service.ai.AnthropicAiAssistService} exactly</strong> in shape —
 * per-tenant API key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the
 * call + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url},
 * default the real Anthropic endpoint, OVERRIDDEN to WireMock in tests). It does <strong>NOT</strong>
 * modify the reused AI core (the {@code AnthropicAiAssistService} / {@code AiUsageRecorder} stay
 * empty-diff); it is a sibling caller, the same posture as {@code ReminderCopyService} /
 * {@code GbpReplyDraftService}.
 *
 * <h2>Tier-aware tone (AR-3)</h2>
 * Three escalating tones, keyed on the {@link DunningLog.DunningTier} crossed:
 * <ul>
 *   <li><strong>D3</strong> — a gentle, friendly first reminder.</li>
 *   <li><strong>D7</strong> — a firmer second notice.</li>
 *   <li><strong>D14</strong> — a final notice (still courteous, never threatening).</li>
 * </ul>
 * The composed body always includes the one-touch Stripe pay-link URL (passed in), and the prompt
 * instructs the model to weave that exact link in verbatim.
 *
 * <h2>Defensive — never throws (the {@code ReminderCopyService} best-effort posture)</h2>
 * The {@link #compose(DunningContext)} call surfaces {@code 1200} (budget exhausted) / {@code 1202}
 * (upstream non-200 or a blank model answer) / {@code 1203} (missing key) as errors so the
 * {@link DunningDispatchService} caller can fall back; but for callers that want a guaranteed body
 * this composer also exposes the deterministic {@link #fallbackBody(DunningTier, String, String, java.math.BigDecimal, String)}
 * per-tier template. The caller wraps the AI call {@code onErrorResume(→ fallbackBody)} so a budget /
 * upstream / missing-key failure degrades to the literal template — the personalization is a nicety,
 * never a blocker, and we never text a blank body. Error codes are reused, not re-allocated (the AI
 * band {@code 1200/1202/1203}); AR-3 mints none of its own (the 4600-4619 band stays for the sweep
 * job's domain, with none needed here by the defensive-fallback design).
 */
@Slf4j
public class DunningCopyComposer {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You write a single short SMS reminding a customer that an invoice is past due, on behalf "
            + "of the business owner. Write in a warm, professional, concise voice — like a real small "
            + "business owner who values the relationship, never a threatening collections agency. "
            + "Greet the customer by first name when one is given, name the invoice number and the "
            + "amount due when given, and adjust the firmness to the tone requested (a gentle first "
            + "reminder, a firmer second notice, or a final notice). Keep it to ONE or TWO short "
            + "sentences that fit comfortably in a text message. You will be given a payment link — "
            + "include it VERBATIM, exactly as provided, so the customer can pay in one touch. Do NOT "
            + "invent facts, amounts, dates, fees, or names not provided. Do NOT add placeholders, "
            + "signatures, emoji, or markdown — output ONLY the SMS text itself (the payment link is "
            + "the one exception, included inline as given).";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String draftModel;
    private final String systemPromptOverride;

    public DunningCopyComposer(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.modules.ar.dunning-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.modules.ar.dunning-system-prompt:}") String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.draftModel = draftModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /** Mirror of {@code ReminderCopyService.DunningTier}-equivalent: the escalating tone selector. */
    public enum DunningTier { D3, D7, D14 }

    /**
     * The personalization inputs the composer weaves into the dunning reminder. All optional/nullable
     * except {@code tier} and {@code payLink} — the prompt instructs the model to skip anything not
     * provided rather than invent it.
     *
     * @param tier             the escalating tier (drives the tone) — required
     * @param customerFirstName the customer's first name (greeting), or null
     * @param invoiceNumber    the human invoice number (e.g. "INV-2026-0007"), or null
     * @param amountDue        the outstanding balance, or null
     * @param currency         the ISO currency (e.g. "USD"), or null (defaults to USD in the fallback)
     * @param daysOverdue      whole days past due (for the model's context), or null
     * @param payLink          the one-touch Stripe pay-link URL to include verbatim — required
     * @param brandTone        the owner's brand-voice hint, or null
     */
    public record DunningContext(
            DunningTier tier,
            String customerFirstName,
            String invoiceNumber,
            BigDecimal amountDue,
            String currency,
            Long daysOverdue,
            String payLink,
            String brandTone) {
    }

    /**
     * Drafts the dunning SMS. Mirrors {@code ReminderCopyService.draftReminder}: tenant key → budget
     * gate → POST → record spend → return the trimmed SMS text. Errors (1200/1202/1203) propagate so
     * the caller can fall back to {@link #fallbackBody} (best-effort).
     */
    public Mono<String> compose(DunningContext context) {
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

    private Mono<CompletionResult> postMessages(String apiKey, DunningContext context) {
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
                                "Anthropic dunning-draft call failed: "
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
            // No usable dunning text — surface as an upstream failure so the caller's best-effort
            // onErrorResume falls back to the literal template (never texts a blank body).
            return Mono.error(new DigiPresBeException(
                    "Anthropic dunning-draft returned an empty reminder", 1202, 502));
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return Mono.just(new CompletionResult(text, inputTokens, outputTokens));
    }

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    private static String buildUserPrompt(DunningContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write a past-due invoice reminder text. Tone: ")
                .append(toneFor(c.tier())).append(".\n\n");
        sb.append("Customer first name: ").append(blankToUnknown(c.customerFirstName())).append('\n');
        sb.append("Invoice number: ").append(blankToUnknown(c.invoiceNumber())).append('\n');
        sb.append("Amount due: ").append(amountPhrase(c.amountDue(), c.currency())).append('\n');
        sb.append("Days past due: ")
                .append(c.daysOverdue() == null ? "(not provided)" : c.daysOverdue().toString())
                .append('\n');
        sb.append("Payment link (include VERBATIM): ").append(c.payLink()).append('\n');
        if (c.brandTone() != null && !c.brandTone().isBlank()) {
            sb.append("Business brand voice / tone to match: ").append(c.brandTone().trim()).append('\n');
        }
        sb.append("\nWrite the SMS now.");
        return sb.toString();
    }

    private static String toneFor(DunningTier tier) {
        return switch (tier) {
            case D3 -> "a gentle, friendly first reminder";
            case D7 -> "a firmer second notice";
            case D14 -> "a final notice (still courteous, never threatening)";
        };
    }

    /**
     * The deterministic per-tier fallback used when Claude is unavailable / budget-exhausted / returned
     * blank — never throws, never blank, always carries the pay link. The {@link DunningDispatchService}
     * degrades to this via {@code onErrorResume}.
     */
    public static String fallbackBody(DunningTier tier, String firstName, String invoiceNumber,
                                      BigDecimal amountDue, String currency, String payLink) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi");
        if (firstName != null && !firstName.isBlank()) sb.append(' ').append(firstName.trim());
        sb.append("! ");
        String amount = amountPhrase(amountDue, currency);
        boolean hasAmount = !"(not provided)".equals(amount);
        switch (tier) {
            case D3 -> {
                sb.append("A friendly reminder that ");
                appendInvoiceClause(sb, invoiceNumber, hasAmount, amount, "is now past due");
                sb.append(". You can pay here: ").append(payLink);
            }
            case D7 -> {
                sb.append("A second reminder that ");
                appendInvoiceClause(sb, invoiceNumber, hasAmount, amount, "remains past due");
                sb.append(". Please pay at your earliest convenience: ").append(payLink);
            }
            case D14 -> {
                sb.append("Final notice: ");
                appendInvoiceClause(sb, invoiceNumber, hasAmount, amount, "is significantly past due");
                sb.append(". Please pay as soon as possible to avoid interruption: ").append(payLink);
            }
        }
        return sb.toString();
    }

    /** Convenience overload from a {@link DunningContext} (used by the caller's fallback path). */
    public static String fallbackBody(DunningContext c) {
        return fallbackBody(c.tier(), c.customerFirstName(), c.invoiceNumber(),
                c.amountDue(), c.currency(), c.payLink());
    }

    private static void appendInvoiceClause(StringBuilder sb, String invoiceNumber,
                                            boolean hasAmount, String amount, String pastDuePhrase) {
        if (invoiceNumber != null && !invoiceNumber.isBlank()) {
            sb.append("invoice ").append(invoiceNumber.trim());
        } else {
            sb.append("your invoice");
        }
        if (hasAmount) {
            sb.append(" (").append(amount).append(')');
        }
        sb.append(' ').append(pastDuePhrase);
    }

    private static String amountPhrase(BigDecimal amount, String currency) {
        if (amount == null) {
            return "(not provided)";
        }
        String cur = (currency == null || currency.isBlank()) ? "USD" : currency.trim();
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + cur;
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
