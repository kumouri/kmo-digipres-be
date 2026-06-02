package com.kumouri.kmodigipresbe.integration.twilio.voice;

import org.springframework.util.MultiValueMap;

/**
 * The parsed Twilio transcription/recording callback parameters (Phase 1 — NMM
 * voicemail-to-lead). Isolates every assumption about the Twilio callback param NAMES
 * to one place (the adapter boundary, like {@code CalComEventAdapter} for Cal.com) —
 * correcting against a real Twilio callback shape is a one-file change here.
 *
 * <p>Twilio POSTs these as {@code application/x-www-form-urlencoded}. The
 * {@code CallSid} is the idempotency/dedupe key; {@code From} is the caller (the
 * find-or-create Contact key); {@code TranscriptionText} is the built-in transcript.
 *
 * @param callSid            the Twilio call SID — the dedupe key (REQUIRED)
 * @param recordingSid       the recording SID (nullable)
 * @param recordingUrl       the recording URL (nullable; not fetched this phase)
 * @param from               the caller's number, E.164 (nullable)
 * @param to                 the dialed business number, E.164 (nullable)
 * @param transcriptionText  Twilio's built-in transcript (nullable/blank tolerated)
 * @param transcriptionStatus the transcription status (e.g. {@code completed}, nullable)
 */
public record VoicemailCallbackParams(
        String callSid,
        String recordingSid,
        String recordingUrl,
        String from,
        String to,
        String transcriptionText,
        String transcriptionStatus) {

    /**
     * Parses the inbound Twilio form params. Null-safe; never throws. The param names
     * ({@code CallSid}, {@code RecordingSid}, {@code RecordingUrl}, {@code From},
     * {@code To}, {@code TranscriptionText}, {@code TranscriptionStatus}) are assumed
     * <em>only</em> here.
     */
    public static VoicemailCallbackParams parse(MultiValueMap<String, String> form) {
        if (form == null) {
            return new VoicemailCallbackParams(null, null, null, null, null, null, null);
        }
        return new VoicemailCallbackParams(
                form.getFirst("CallSid"),
                form.getFirst("RecordingSid"),
                form.getFirst("RecordingUrl"),
                form.getFirst("From"),
                form.getFirst("To"),
                form.getFirst("TranscriptionText"),
                form.getFirst("TranscriptionStatus"));
    }
}
