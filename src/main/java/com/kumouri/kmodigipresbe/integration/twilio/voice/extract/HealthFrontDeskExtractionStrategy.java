package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailCallbackParams;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailExtractionService;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.HealthIntakeExtraction.IntentBucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * The health-practices front-desk voicemail-extraction strategy (FrontDesk IQ FD-3 — flagship #4).
 * A <strong>thin caller</strong> over the shared {@link VoicemailExtractionService} transport — owning
 * only the logistics-only {@link #SYSTEM_PROMPT}, the {@code kmosf.voicemail.extraction-model} model
 * choice, and the mapping of the returned JSON into a {@link HealthIntakeExtraction} and then into a
 * {@link VoicemailLeadDetails}. Exactly the {@link MultiTradeExtractionStrategy} relationship to the
 * transport, minus the WorkOrder and minus any transcript-in-notes.
 *
 * <p>{@link #verticalKey()} is {@code "health-frontdesk"} — the resolver
 * ({@link VoicemailExtractionStrategyResolver}) returns this strategy when a tenant's
 * {@code IntegrationConnection(twilio).config.voicemailVertical = "health-frontdesk"}. NMM (no config
 * key) still defaults to the mole strategy, so this is purely additive — zero blast radius.
 *
 * <h2>FD-3 fence F2 — PHI-free by construction</h2>
 * The whole pitch of FrontDesk IQ is "a smarter front desk that never touches the chart." This
 * strategy is fenced on three of FD-3's surfaces:
 * <ol>
 *   <li><strong>Logistics-only schema (the structural fence):</strong> {@link HealthIntakeExtraction}
 *       has no symptom/diagnosis/procedure/drug field, so the spoken clinical detail cannot be
 *       captured even if the model emitted it. The extracted {@code extractedJson} this strategy
 *       echoes onto the Activity payload carries ONLY {@code name / callbackNumber / intentBucket /
 *       callbackRequested} — never the transcript, never a clinical token.</li>
 *   <li><strong>{@link #persistTranscript()} = {@code false} (the data-layer fence):</strong>
 *       {@code TwilioVoicemailService.logCallActivity} reads this and stores a fixed redaction marker
 *       as the {@code Activity.body} instead of the raw transcript (and omits the recording pointer) —
 *       so a patient saying "I need my insulin refilled" never lands in a stored, queryable record.
 *       That a {@code CALL} Activity is also never embedded (the {@code EmbeddingPipeline} indexes only
 *       NOTE/EMAIL) is defense-in-depth.</li>
 *   <li><strong>The prompt (the last fence):</strong> {@link #SYSTEM_PROMPT} explicitly instructs the
 *       model to capture only the routing bucket + callback and to <strong>drop any clinical/symptom/
 *       medication detail</strong>. The prompt is the last of the fences, never the only one.</li>
 * </ol>
 *
 * <p><strong>{@link #toDraftWorkOrder} is always {@code null}</strong> — a health-practice voicemail
 * creates no {@code WorkOrder} (that is a home-services concept). The resulting
 * {@link VoicemailLeadDetails} carries a {@code null} {@code draftWorkOrder}, so the existing pipeline
 * creates a Contact + a callback {@code Activity(CALL, INBOUND)} + best-effort notify only.
 *
 * <p><strong>A callback is never dropped:</strong> a blank or unparseable extraction (or an AI
 * budget/upstream failure — {@code 1200-1203}) degrades to {@link HealthIntakeExtraction#empty()}
 * ({@code OTHER} bucket), so the front desk still gets a clean callback card (name + caller-ID number)
 * — but the transcript that would let a human re-read the clinical content is deliberately never kept
 * (fence F2; verifiable transcripts are a separately-priced compliance-tier conversation, plan §0/D4).
 */
@Slf4j
@Component
public class HealthFrontDeskExtractionStrategy implements VoicemailExtractionStrategy {

    public static final String VERTICAL_KEY = "health-frontdesk";

    private static final String SYSTEM_PROMPT =
            "You triage the transcript of an after-hours voicemail left for a health practice "
            + "(a dental, medical, or veterinary front desk). Your ONLY job is to capture front-desk "
            + "LOGISTICS so the office can return the call — you are NOT a clinical system. Respond "
            + "with ONLY a single minified JSON object and nothing else — no prose, no markdown, no "
            + "code fences. The object MUST have exactly these keys: \"name\" (the caller's name, or "
            + "null), \"callbackNumber\" (a callback number stated in the message, or null), "
            + "\"intentBucket\" (the reason for the call as a LOGISTICS routing bucket — one of "
            + "\"SCHEDULING\" (book/reschedule/cancel an appointment), \"BILLING\" (a bill, payment, "
            + "or insurance question), \"PRESCRIPTION_REFILL_REQUEST\" (the caller wants a "
            + "prescription refilled — capture ONLY that they want a refill, NEVER the medication "
            + "name or dosage), \"GENERAL_CALLBACK\" (a general request to be called back), or "
            + "\"OTHER\" if it does not fit), and \"callbackRequested\" (boolean true if the caller "
            + "asked to be called back, else false). "
            + "CRITICAL — you MUST NOT include any clinical detail anywhere in your response: do NOT "
            + "echo symptoms, conditions, diagnoses, procedures, body parts, medication names, "
            + "dosages, or the reason the caller is unwell. If the caller describes a medical problem, "
            + "ignore the clinical content entirely and capture only the logistics bucket above. Use "
            + "null for name/callbackNumber if not present. Do not invent values; this is a triage "
            + "hint a human will confirm.";

    private final VoicemailExtractionService transport;
    private final String extractionModel;

    public HealthFrontDeskExtractionStrategy(
            VoicemailExtractionService transport,
            @Value("${kmosf.voicemail.extraction-model:claude-haiku-4-5}") String extractionModel) {
        this.transport = transport;
        this.extractionModel = extractionModel;
    }

    @Override
    public String verticalKey() {
        return VERTICAL_KEY;
    }

    /**
     * FD-3 fence F2 — the raw transcript is NEVER persisted for the health front-desk vertical.
     * {@code TwilioVoicemailService.logCallActivity} reads this and stores a redaction marker instead
     * of the transcript (and omits the recording pointer).
     */
    @Override
    public boolean persistTranscript() {
        return false;
    }

    @Override
    public Mono<VoicemailLeadDetails> extract(String transcript, VoicemailCallbackParams params) {
        return transport.extractRaw(transcript, extractionModel, SYSTEM_PROMPT)
                .map(node -> toDetails(parse(node), params))
                .onErrorResume(e -> {
                    // A callback is NEVER dropped: an AI budget/upstream failure (1200-1203) still
                    // produces a usable logistics-only callback (name + caller-ID), never the
                    // transcript. The body redaction (F2) is enforced downstream regardless.
                    log.warn("Health front-desk voicemail extraction failed (best-effort, "
                            + "logistics-only callback): {}", e.getMessage());
                    return Mono.just(toDetails(HealthIntakeExtraction.empty(), params));
                });
    }

    /** Defensive field parse of the raw JSON into a {@link HealthIntakeExtraction} (never throws). */
    private HealthIntakeExtraction parse(JsonNode node) {
        return new HealthIntakeExtraction(
                textOrNull(node, "name"),
                textOrNull(node, "callbackNumber"),
                IntentBucket.fromWire(textOrNull(node, "intentBucket")),
                node.path("callbackRequested").asBoolean(false));
    }

    /**
     * Maps the logistics-only {@link HealthIntakeExtraction} into the common
     * {@link VoicemailLeadDetails}. The {@code extractedJson} echoed onto the Activity payload carries
     * ONLY logistics fields — name, callbackNumber, intentBucket, callbackRequested — and NEVER the
     * transcript or any clinical token (fence F2). {@code draftWorkOrder} is {@code null} (health
     * creates no WorkOrder); the transcript is NOT passed through here at all.
     */
    private VoicemailLeadDetails toDetails(HealthIntakeExtraction ex, VoicemailCallbackParams params) {
        Map<String, Object> extractedJson = new HashMap<>();
        if (ex.name() != null) extractedJson.put("name", ex.name());
        if (ex.callbackNumber() != null) extractedJson.put("callbackNumber", ex.callbackNumber());
        extractedJson.put("intentBucket", ex.intentBucket().wire());
        extractedJson.put("callbackRequested", ex.callbackRequested());

        return new VoicemailLeadDetails(
                ex.name(),
                ex.callbackNumber(),
                null, // no service address — a health practice voicemail captures no address
                ex.callbackRequested(),
                toSummaryLine(ex, params),
                extractedJson,
                null); // health creates no WorkOrder from a voicemail
    }

    /**
     * A one-line, PHI-free human summary for the callback card (Activity summary + notify body):
     * "Callback — Prescription refill" / "Callback from Dana — Scheduling — callback requested". Built
     * from the logistics bucket + (optional) name only; never a clinical token.
     */
    private static String toSummaryLine(HealthIntakeExtraction ex, VoicemailCallbackParams params) {
        StringBuilder sb = new StringBuilder("Callback");
        if (ex.name() != null && !ex.name().isBlank()) {
            sb.append(" from ").append(ex.name().trim());
        }
        sb.append(" — ").append(ex.intentBucket().label());
        if (ex.callbackRequested()) {
            sb.append(" — callback requested");
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
