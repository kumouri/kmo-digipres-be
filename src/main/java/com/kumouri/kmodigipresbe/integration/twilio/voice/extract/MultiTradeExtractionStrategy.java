package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailCallbackParams;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailExtractionService;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.MultiTradeExtraction.JobValueBand;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.MultiTradeExtraction.Trade;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.MultiTradeExtraction.Urgency;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The home-services multi-trade voicemail-extraction strategy (HS-1 — Home Services "Front Desk
 * That Never Sleeps"). A <strong>thin caller</strong> over the shared
 * {@link VoicemailExtractionService} transport — owning only the multi-trade {@link #SYSTEM_PROMPT},
 * the {@code kmosf.voicemail.extraction-model} model choice, and the mapping of the returned JSON
 * into a {@link MultiTradeExtraction} and then into a {@link VoicemailLeadDetails} that carries a
 * <strong>DRAFT {@link WorkOrder}</strong>. Exactly the {@code MoleVisionService} →
 * {@code AiVisionService} relationship.
 *
 * <p>{@link #verticalKey()} is {@code "home-services"} — the resolver returns this strategy when
 * {@code IntegrationConnection(twilio).config.voicemailVertical = "home-services"}.
 *
 * <p><strong>A lead is never dropped:</strong> even a blank or unparseable extraction yields a DRAFT
 * WorkOrder with {@code serviceType="GENERAL"} and the raw transcript in the notes (plan §4.8 case
 * 5 — "AI is triage" rule). {@code urgency} is the routing key (recorded in {@code customFields};
 * HS-3 acts on {@code EMERGENCY}); {@code scheduledStart} is left {@code null} on purpose so the
 * DRAFT stays off the dated dispatch board and surfaces only in the Missed-Call Inbox until a human
 * schedules it (plan §4.3 / §6 dispatch-board coupling).
 */
@Slf4j
@Component
public class MultiTradeExtractionStrategy implements VoicemailExtractionStrategy {

    public static final String VERTICAL_KEY = "home-services";

    private static final String SYSTEM_PROMPT =
            "You triage the transcript of a voicemail left for a home-services contractor (HVAC, "
            + "plumbing, electrical, roofing, or pest control). Respond with ONLY a single minified "
            + "JSON object and nothing else — no prose, no markdown, no code fences. The object MUST "
            + "have exactly these keys: \"name\" (the caller's name, or null), \"phone\" (a callback "
            + "number stated in the message, or null), \"address\" (the service address, or null), "
            + "\"trade\" (the discipline this job needs — one of \"HVAC\", \"PLUMBING\", "
            + "\"ELECTRICAL\", \"ROOFING\", \"PEST\", or \"GENERAL\" if it does not clearly fit one, "
            + "or null if you cannot tell), \"urgency\" (one of \"EMERGENCY\" for a safety/no-heat/"
            + "flood/no-power situation needing same-day attention, \"URGENT\" for a problem needing "
            + "attention within a day or two, or \"ROUTINE\" for a non-urgent request — or null if "
            + "unclear), \"symptom\" (a short description of the problem, e.g. \"no heat, furnace "
            + "clicking\", or null), \"jobValueBand\" (a coarse cost band — \"SMALL\", \"MEDIUM\", or "
            + "\"LARGE\" — or null), and \"callbackRequested\" (boolean true if the caller asked to "
            + "be called back, else false). Use null for any field not present in the transcript. Do "
            + "not invent values; this is a triage hint a human will confirm.";

    private final VoicemailExtractionService transport;
    private final String extractionModel;

    public MultiTradeExtractionStrategy(
            VoicemailExtractionService transport,
            @Value("${kmosf.voicemail.extraction-model:claude-haiku-4-5}") String extractionModel) {
        this.transport = transport;
        this.extractionModel = extractionModel;
    }

    @Override
    public String verticalKey() {
        return VERTICAL_KEY;
    }

    @Override
    public Mono<VoicemailLeadDetails> extract(String transcript, VoicemailCallbackParams params) {
        return transport.extractRaw(transcript, extractionModel, SYSTEM_PROMPT)
                .map(node -> toDetails(node, transcript, params));
    }

    private VoicemailLeadDetails toDetails(JsonNode node, String transcript,
                                           VoicemailCallbackParams params) {
        MultiTradeExtraction ex = new MultiTradeExtraction(
                textOrNull(node, "name"),
                textOrNull(node, "phone"),
                textOrNull(node, "address"),
                Trade.fromWire(textOrNull(node, "trade")),
                Urgency.fromWire(textOrNull(node, "urgency")),
                textOrNull(node, "symptom"),
                JobValueBand.fromWire(textOrNull(node, "jobValueBand")),
                node.path("callbackRequested").asBoolean(false));

        Map<String, Object> extractedJson = new HashMap<>();
        if (ex.name() != null) extractedJson.put("name", ex.name());
        if (ex.phone() != null) extractedJson.put("phone", ex.phone());
        if (ex.address() != null) extractedJson.put("address", ex.address());
        if (ex.trade() != null) extractedJson.put("trade", ex.trade().wire());
        if (ex.urgency() != null) extractedJson.put("urgency", ex.urgency().wire());
        if (ex.symptom() != null) extractedJson.put("symptom", ex.symptom());
        if (ex.jobValueBand() != null) extractedJson.put("jobValueBand", ex.jobValueBand().wire());
        extractedJson.put("callbackRequested", ex.callbackRequested());

        return new VoicemailLeadDetails(
                ex.name(),
                ex.phone(),
                ex.address(),
                ex.callbackRequested(),
                toSummaryLine(ex),
                extractedJson,
                toDraftWorkOrder(ex, transcript, params));
    }

    /**
     * Builds the DRAFT {@link WorkOrder} for this voicemail. ALWAYS non-null for the home-services
     * vertical — a lead is never dropped. {@code serviceType} is the extracted trade or
     * {@code GENERAL} when unknown; {@code title} is {@code "<TRADE> — <URGENCY>"};
     * {@code notes} carries the symptom + the raw transcript; {@code customFields} carry the
     * routing/triage fields (urgency, jobValueBand, callSid). {@code jobSiteId} and
     * {@code scheduledStart} are intentionally null (staff link + schedule in the Missed-Call
     * Inbox). The {@code workOrderNumber} is NOT set here — {@code WorkOrderService.create}
     * server-assigns it.
     */
    private WorkOrder toDraftWorkOrder(MultiTradeExtraction ex, String transcript,
                                       VoicemailCallbackParams params) {
        String trade = ex.trade() != null ? ex.trade().wire() : Trade.GENERAL.wire();
        String urgencyLabel = ex.urgency() != null ? ex.urgency().wire() : "UNTRIAGED";

        // Insertion-ordered so the WorkOrder.customFields serialize predictably.
        Map<String, Object> customFields = new LinkedHashMap<>();
        customFields.put("urgency", urgencyLabel);
        if (ex.jobValueBand() != null) customFields.put("jobValueBand", ex.jobValueBand().wire());
        if (params != null && params.callSid() != null) customFields.put("callSid", params.callSid());
        // Origin marker so the Missed-Call Inbox can filter voicemail-sourced DRAFTs.
        customFields.put("source", "voicemail");

        return WorkOrder.builder()
                .status(WorkOrderStatus.DRAFT)
                .serviceType(trade)
                .title(trade + " — " + urgencyLabel)
                .notes(buildNotes(ex, transcript))
                .customFields(customFields)
                .build();
    }

    private static String buildNotes(MultiTradeExtraction ex, String transcript) {
        StringBuilder sb = new StringBuilder("Missed-call voicemail lead (home services).");
        if (ex.symptom() != null && !ex.symptom().isBlank()) {
            sb.append("\nSymptom: ").append(ex.symptom().trim());
        }
        if (ex.address() != null && !ex.address().isBlank()) {
            sb.append("\nService address: ").append(ex.address().trim());
        }
        if (transcript != null && !transcript.isBlank()) {
            sb.append("\n\nTranscript:\n").append(transcript.trim());
        }
        return sb.toString();
    }

    /** A one-line human summary (Activity summary + notify body) built defensively. */
    private static String toSummaryLine(MultiTradeExtraction ex) {
        StringBuilder sb = new StringBuilder("Voicemail lead");
        if (ex.name() != null && !ex.name().isBlank()) sb.append(" from ").append(ex.name().trim());
        if (ex.symptom() != null && !ex.symptom().isBlank()) sb.append(": ").append(ex.symptom().trim());
        String trade = ex.trade() != null ? ex.trade().wire() : "GENERAL";
        sb.append(" (").append(trade);
        if (ex.urgency() != null) sb.append(", urgency: ").append(ex.urgency().wire());
        sb.append(")");
        if (ex.callbackRequested()) sb.append(" — callback requested");
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
