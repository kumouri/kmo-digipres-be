package com.kumouri.kmodigipresbe.integration.twilio.voice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the default {@link VoicemailTranscriptionSource} (Phase 1 — NMM voicemail-to-lead).
 *
 * <p>The default {@link InboundTranscriptionTextSource} (Twilio's built-in transcription) is
 * provided via {@code @Bean @ConditionalOnMissingBean} — the idiomatic "default unless
 * overridden" seam. A future Whisper-backed {@link VoicemailTranscriptionSource} (default-OFF,
 * the plan §3 recommendation) replaces it by simply being registered as a bean; the voicemail
 * service depends on the interface, not the impl.
 *
 * <p><strong>Why a {@code @Bean} method and not {@code @Component} on the impl:</strong>
 * {@code @ConditionalOnMissingBean} is only evaluated reliably on a {@code @Bean} factory
 * method. On a component-scanned {@code @Component} the condition is evaluated in scan order and
 * the bean may never register — which leaves {@code TwilioVoicemailService} without its required
 * {@link VoicemailTranscriptionSource} and fails context startup.
 */
@Configuration
public class VoicemailTranscriptionConfig {

    @Bean
    @ConditionalOnMissingBean(VoicemailTranscriptionSource.class)
    VoicemailTranscriptionSource defaultVoicemailTranscriptionSource() {
        return new InboundTranscriptionTextSource();
    }
}
