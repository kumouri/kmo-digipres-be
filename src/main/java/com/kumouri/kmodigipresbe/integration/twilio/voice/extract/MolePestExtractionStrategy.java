package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailCallbackParams;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailExtraction;
import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * The default voicemail-extraction strategy — pest-control / mole-removal (HS-1 — Home Services
 * front desk). A <strong>thin caller</strong> over the shared
 * {@link VoicemailExtractionService} transport, owning only the mole {@link #SYSTEM_PROMPT}, the
 * {@code kmosf.voicemail.extraction-model} model choice, and the mapping of the returned JSON into
 * the Phase-1 {@link VoicemailExtraction} record — exactly the {@code MoleVisionService} →
 * {@code AiVisionService} relationship.
 *
 * <p><strong>The mole {@link #SYSTEM_PROMPT} string, the default model {@code claude-haiku-4-5},
 * and the {@link VoicemailExtraction} field-mapping are moved here VERBATIM from the Phase-1
 * {@code VoicemailExtractionService}</strong> (a git-visible cut/paste; the literals are
 * identical). The Anthropic wire request for a mole tenant is therefore byte-identical to Phase 1,
 * and {@code TwilioVoicemailIT}'s WireMock stub + {@code verify(1, ...)} pass unchanged.
 *
 * <p>{@link #verticalKey()} is {@code "mole-pest"} — the resolver returns this strategy for that
 * value AND for an absent {@code voicemailVertical} key, so NMM (no config key) defaults here.
 * {@link #toDraftWorkOrder} returns {@code null}: NMM does <strong>not</strong> create a
 * {@code WorkOrder} from a voicemail (unchanged behavior), so the produced
 * {@link VoicemailLeadDetails} carries a {@code null} {@code draftWorkOrder}.
 *
 * <p>The {@code extractedJson} map and {@code summaryLine} this strategy produces reproduce the
 * Phase-1 {@code TwilioVoicemailService.logCallActivity} construction byte-for-byte (only non-null
 * fields added + {@code callbackRequested} always present; summary from
 * {@link VoicemailExtraction#toSummaryLine()}), so the mole tenant's resulting Contact / Activity
 * body+payload / notify all stay identical.
 */
@Slf4j
@Component
public class MolePestExtractionStrategy implements VoicemailExtractionStrategy {

    public static final String VERTICAL_KEY = "mole-pest";

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

    private final VoicemailExtractionService transport;
    private final String extractionModel;

    public MolePestExtractionStrategy(
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
                .map(this::toDetails);
    }

    /**
     * Maps the raw {@link JsonNode} into a {@link VoicemailExtraction} (the Phase-1 defensive
     * field parse — a missing/blank field is {@code null}) and then into the common
     * {@link VoicemailLeadDetails}. {@code draftWorkOrder} is {@code null} (NMM creates no WO).
     */
    private VoicemailLeadDetails toDetails(JsonNode node) {
        VoicemailExtraction extraction = new VoicemailExtraction(
                textOrNull(node, "name"),
                textOrNull(node, "phone"),
                textOrNull(node, "address"),
                textOrNull(node, "problem"),
                textOrNull(node, "urgency"),
                node.path("callbackRequested").asBoolean(false));

        // Reproduce the Phase-1 logCallActivity extractedJson construction byte-for-byte:
        // only non-null fields are added; callbackRequested is always present.
        Map<String, Object> extractedJson = new HashMap<>();
        if (extraction.name() != null) extractedJson.put("name", extraction.name());
        if (extraction.phone() != null) extractedJson.put("phone", extraction.phone());
        if (extraction.address() != null) extractedJson.put("address", extraction.address());
        if (extraction.problem() != null) extractedJson.put("problem", extraction.problem());
        if (extraction.urgency() != null) extractedJson.put("urgency", extraction.urgency());
        extractedJson.put("callbackRequested", extraction.callbackRequested());

        return new VoicemailLeadDetails(
                extraction.name(),
                extraction.phone(),
                extraction.address(),
                extraction.callbackRequested(),
                extraction.toSummaryLine(),
                extractedJson,
                null); // mole creates no WorkOrder from a voicemail (unchanged NMM behavior)
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
