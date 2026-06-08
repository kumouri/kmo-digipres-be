package com.kumouri.kmodigipresbe.module.frontdesk.ai;

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
 * FrontDesk IQ (FD-2) — drafts a short, on-brand, <strong>PHI-free</strong> appointment confirmation /
 * reminder SMS for an upcoming health {@code Appointment}. A sibling of the chairfill
 * {@link com.kumouri.kmodigipresbe.module.chairfill.ai.ReminderCopyService} in shape — per-tenant API key
 * from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the call +
 * spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url}, default the real
 * Anthropic endpoint, OVERRIDDEN to WireMock in tests). Reuses the AI band codes unchanged
 * ({@code 1200/1201} budget, {@code 1202} upstream non-200 / blank, {@code 1203} missing-key).
 *
 * <h2>The PHI fence F3, enforced by construction</h2>
 * <p>FrontDesk IQ's headline is that outbound copy <strong>never</strong> names a procedure, provider, or
 * visit-type detail (fence F3, FrontDesk IQ plan §0). This service makes that <em>provable</em> at two
 * layers, not as a bolt-on prompt instruction:
 * <ol>
 *   <li><strong>The model is given NO clinical input.</strong> {@link ConfirmationContext} carries ONLY
 *       {@code {clientFirstName, appointmentWhen, brandTone, confirm}} — there is deliberately no
 *       service/provider/visit-type/procedure field for the caller to pass and the prompt to echo. Unlike
 *       the salon {@code ReminderCopyService.ReminderContext} (which carries {@code stylistName} +
 *       {@code lastService}), this record physically cannot transport a clinical token to the LLM. The
 *       model literally cannot leak what it never received.</li>
 *   <li><strong>The system prompt hard-forbids inventing one.</strong> It is told to write a generic
 *       "time for your visit" SMS and to NEVER name or invent a procedure, treatment, diagnosis, provider
 *       name, department, or visit-type — only the time and an optional first-name greeting.</li>
 * </ol>
 * A release-blocking IT ({@code FrontDeskConfirmationIT}) asserts a forbidden clinical/provider-token set
 * is absent from every outbound SMS body (both the Claude path and the generic fallback), so the boundary
 * cannot drift open. The caller ({@code FrontDeskConfirmationService}) wraps this best-effort: a budget
 * {@code 1200}, an upstream {@code 1202}, or a missing-key {@code 1203} all degrade to the equally-generic
 * deterministic template — personalization is a nicety, never a blocker.
 */
@Slf4j
public class ConfirmationCopyService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    /**
     * The F3 guardrail system prompt. Deliberately phrased so the model has nothing clinical to say and is
     * forbidden from inventing any — the SMS is a logistics reminder, never a description of care.
     */
    static final String DEFAULT_SYSTEM_PROMPT =
            "You write a single short SMS reminding a patient about an upcoming appointment, on behalf of a "
            + "health practice's front desk. Write in a warm, friendly, concise voice — like a real front-desk "
            + "person, not a corporate brand. Greet the patient by first name when one is given, and mention "
            + "the appointment time when it is given. If asked to confirm, ask them to reply to confirm. "
            + "CRITICAL PRIVACY RULE: this practice never discusses a patient's care over SMS. You must write "
            + "GENERIC copy only — say \"your visit\" or \"your appointment\". You must NEVER name, describe, "
            + "guess, or invent any procedure, treatment, diagnosis, symptom, medication, test, body part, "
            + "department, specialty, provider name, or visit type. Do NOT invent facts, times, names, or "
            + "details not provided. Do NOT include placeholders, links, signatures, emoji, or markdown — "
            + "output ONLY the SMS text itself, nothing else.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String draftModel;
    private final String systemPromptOverride;

    public ConfirmationCopyService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.frontdesk.confirmation-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.frontdesk.confirmation-system-prompt:}") String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.draftModel = draftModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * The personalization inputs the drafter weaves into the confirmation. <strong>Logistics-only by
     * construction (fence F3):</strong> there is no service / provider / visit-type field, so the LLM is
     * never handed a clinical token to echo. All optional/nullable — the prompt instructs the model to skip
     * anything not provided rather than invent it.
     *
     * @param clientFirstName the patient's first name (greeting), or null
     * @param appointmentWhen a human phrase for the appointment time (e.g. "Thursday at 2:00 PM"), or null
     * @param brandTone       the practice's brand-voice hint (e.g. "warm and reassuring"), or null
     * @param confirm         true for a HIGH-risk extra confirmation (ask them to reply to confirm);
     *                        false for a light LOW/MEDIUM reminder
     */
    public record ConfirmationContext(
            String clientFirstName,
            String appointmentWhen,
            String brandTone,
            boolean confirm) {
    }

    /**
     * Drafts the confirmation/reminder SMS. Mirrors {@code ReminderCopyService.draftReminder}: tenant key →
     * budget gate → POST → record spend → return the trimmed SMS text. Errors (1200/1202/1203) propagate so
     * the caller can fall back to a generic template (best-effort).
     */
    public Mono<String> draftConfirmation(ConfirmationContext context) {
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

    private Mono<CompletionResult> postMessages(String apiKey, ConfirmationContext context) {
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
                                "Anthropic confirmation-draft call failed: "
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
            // No usable confirmation text — surface as an upstream failure so the caller's best-effort
            // onErrorResume falls back to the generic template (never texts a blank body).
            return Mono.error(new DigiPresBeException(
                    "Anthropic confirmation-draft returned an empty body", 1202, 502));
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return Mono.just(new CompletionResult(text, inputTokens, outputTokens));
    }

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    private static String buildUserPrompt(ConfirmationContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.confirm()
                ? "Write an appointment CONFIRMATION reminder text (ask the patient to reply to confirm).\n\n"
                : "Write a friendly appointment reminder text.\n\n");
        sb.append("Patient first name: ")
                .append(blankToUnknown(c.clientFirstName())).append('\n');
        sb.append("Appointment time: ")
                .append(blankToUnknown(c.appointmentWhen())).append('\n');
        if (c.brandTone() != null && !c.brandTone().isBlank()) {
            sb.append("Practice brand voice / tone to match: ").append(c.brandTone().trim()).append('\n');
        }
        sb.append("\nRemember: GENERIC copy only — never name a procedure, provider, department, or visit "
                + "type. Write the SMS now.");
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
