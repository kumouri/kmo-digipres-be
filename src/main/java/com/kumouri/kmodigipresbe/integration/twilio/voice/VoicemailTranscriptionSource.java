package com.kumouri.kmodigipresbe.integration.twilio.voice;

import reactor.core.publisher.Mono;

/**
 * The pluggable transcription seam (Phase 1 — NMM voicemail-to-lead). Resolves a
 * {@link VoicemailTranscription} from the inbound Twilio voicemail callback params.
 *
 * <p>The default impl ({@link InboundTranscriptionTextSource}) simply uses Twilio's
 * built-in transcription (the {@code TranscriptionText} param) — NO net-new live STT this
 * phase (§7 hard boundary: no net-new live outbound transcription call). A future
 * Whisper-backed impl (OpenAI Whisper STT on the fetched recording bytes, on
 * {@code Schedulers.boundedElastic()}) is the documented, default-OFF seam the plan
 * (§3) recommends — it would be a new {@code @ConditionalOnProperty}-gated bean
 * implementing this same interface, replacing the default without touching the
 * voicemail service.
 */
public interface VoicemailTranscriptionSource {

    /**
     * Produces the voicemail transcript from the callback parameters.
     *
     * @param params the Twilio transcription-callback form params (already parsed)
     * @return the transcript value (its text may be blank — downstream tolerates it)
     */
    Mono<VoicemailTranscription> transcribe(VoicemailCallbackParams params);
}
