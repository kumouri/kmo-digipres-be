package com.kumouri.kmodigipresbe.integration.twilio.voice;

/**
 * The structured lead fields extracted from a voicemail transcript by
 * {@link VoicemailExtractionService} (Phase 1 — NMM voicemail-to-lead). Every field is
 * nullable/blank-tolerant — the LLM may not find a value, and a missing/blank field must
 * never fail the ingest (the transcript + raw recording are always attached so Rob can
 * verify; AI is triage, not truth — plan §8).
 *
 * @param name              the caller's name, if stated (nullable)
 * @param phone             a callback number stated in the message (nullable; the Twilio
 *                          {@code From} caller-ID is captured independently of the transcript)
 * @param address           the service address, if stated (nullable)
 * @param problem           a short description of the mole/pest problem (nullable)
 * @param urgency           the caller's stated urgency, if any (nullable)
 * @param callbackRequested whether the caller asked for a callback (defaults false)
 */
public record VoicemailExtraction(
        String name,
        String phone,
        String address,
        String problem,
        String urgency,
        boolean callbackRequested) {

    /** An all-empty extraction — used when the transcript is blank or extraction fails soft. */
    public static VoicemailExtraction empty() {
        return new VoicemailExtraction(null, null, null, null, null, false);
    }

    /**
     * A one-line human summary for the Activity summary + the notify-Rob message. Built
     * defensively from whatever fields are present.
     */
    public String toSummaryLine() {
        StringBuilder sb = new StringBuilder("Voicemail lead");
        if (name != null && !name.isBlank()) sb.append(" from ").append(name.trim());
        if (problem != null && !problem.isBlank()) sb.append(": ").append(problem.trim());
        if (urgency != null && !urgency.isBlank()) sb.append(" (urgency: ").append(urgency.trim()).append(")");
        if (callbackRequested) sb.append(" — callback requested");
        return sb.toString();
    }
}
