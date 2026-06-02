package com.kumouri.kmodigipresbe.integration.twilio.voice;

/**
 * The transcript of a voicemail recording, plus its provenance (Phase 1 — NMM
 * voicemail-to-lead). A small value type at the transcription seam.
 *
 * @param text   the transcript text (may be blank if Twilio's transcription failed or
 *               returned empty — downstream extraction tolerates blank)
 * @param source which transcription source produced this text (provenance only)
 */
public record VoicemailTranscription(String text, Source source) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /**
     * Transcription provenance. Today only {@link #TWILIO} is wired (the inbound
     * {@code TranscriptionText} param). {@link #WHISPER} is a documented future seam
     * (an OpenAI Whisper STT pass on the fetched recording) — NOT built this phase and
     * default-OFF; the plan (§3) recommends it later. Keeping the enum lets a future
     * {@code VoicemailTranscriptionSource} impl set its provenance without a model change.
     */
    public enum Source {
        /** Twilio's built-in transcription (the inbound {@code TranscriptionText} param). */
        TWILIO,
        /** Reserved: a future OpenAI Whisper STT pass on the fetched recording (NOT built). */
        WHISPER
    }
}
