package com.kumouri.kmodigipresbe.integration.twilio.voice;

import reactor.core.publisher.Mono;

/**
 * Default {@link VoicemailTranscriptionSource} (Phase 1 — NMM voicemail-to-lead): uses
 * Twilio's built-in transcription, i.e. the inbound {@code TranscriptionText} param.
 * No net-new live STT call (§7 — no net-new live outbound transcription this phase).
 *
 * <p>Registered as the default {@link VoicemailTranscriptionSource} via
 * {@code @Bean @ConditionalOnMissingBean} in {@link VoicemailTranscriptionConfig} (the idiomatic
 * "default unless overridden" seam). A future Whisper-backed impl (default-OFF, the plan's §3
 * recommendation) replaces it by simply being registered — the voicemail service depends on the
 * interface, not this impl.
 */
public class InboundTranscriptionTextSource implements VoicemailTranscriptionSource {

    @Override
    public Mono<VoicemailTranscription> transcribe(VoicemailCallbackParams params) {
        String text = params == null ? null : params.transcriptionText();
        return Mono.just(new VoicemailTranscription(text, VoicemailTranscription.Source.TWILIO));
    }
}
