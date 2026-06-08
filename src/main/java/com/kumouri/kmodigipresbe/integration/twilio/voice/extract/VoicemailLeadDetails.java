package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;

import java.util.HashMap;
import java.util.Map;

/**
 * The vertical-agnostic carrier the voicemail orchestration consumes (HS-1 — Home Services
 * front desk). A {@link VoicemailExtractionStrategy} maps its own richer per-vertical record
 * (the mole {@code VoicemailExtraction}, the {@link MultiTradeExtraction}) into this common
 * shape so {@code TwilioVoicemailService}'s find-or-create-Contact / log-{@code Activity} /
 * notify steps stay strategy-independent.
 *
 * <p>The four common fields ({@link #name}/{@link #phone}/{@link #address} +
 * {@link #callbackRequested}) feed the Contact build; {@link #summaryLine} is the
 * {@code Activity.summary} + the notify one-liner; {@link #extractedJson} is echoed verbatim
 * into the {@code Activity.payload.extractedJson} map; {@link #draftWorkOrder} is non-null only
 * for verticals that turn a voicemail into a DRAFT {@link WorkOrder} (multi-trade) and
 * {@code null} for verticals that do not (mole — unchanged NMM behavior).
 *
 * <p>Every field is nullable/blank-tolerant — AI is triage, not truth (plan §8); the raw
 * transcript + recording are always attached so a human can verify.
 *
 * @param name              the caller's name, if stated (nullable)
 * @param phone             a callback number stated in the message (nullable; the Twilio
 *                          {@code From} caller-ID is captured independently)
 * @param address           the service address, if stated (nullable)
 * @param callbackRequested whether the caller asked for a callback (defaults false)
 * @param summaryLine       the one-line human summary (Activity summary + notify body)
 * @param extractedJson     the per-vertical extracted fields, echoed into the Activity payload
 * @param draftWorkOrder    a DRAFT {@link WorkOrder} to create for this voicemail, or
 *                          {@code null} when the vertical creates none (mole)
 */
public record VoicemailLeadDetails(
        String name,
        String phone,
        String address,
        boolean callbackRequested,
        String summaryLine,
        Map<String, Object> extractedJson,
        WorkOrder draftWorkOrder) {

    public VoicemailLeadDetails {
        // Defensive copy + null-guard so the orchestration can iterate the map freely.
        extractedJson = extractedJson == null
                ? Map.of()
                : new HashMap<>(extractedJson);
    }
}
