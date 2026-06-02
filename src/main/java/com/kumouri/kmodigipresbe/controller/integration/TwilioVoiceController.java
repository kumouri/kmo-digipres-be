package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.twilio.voice.TwilioVoicemailService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Unauthenticated Twilio <em>incoming-call voice</em> webhook (Phase 1 — NMM
 * voicemail-to-lead). Twilio POSTs here when a forwarded call reaches the pipeline; the
 * BE responds with <strong>TwiML XML</strong> instructing Twilio to greet the caller and
 * record + transcribe a voicemail, with the transcription delivered to the companion
 * {@link TwilioVoicemailController} callback.
 *
 * <p>Tenant id is in the URL path because the caller (Twilio) doesn't carry a JWT — Twilio
 * authenticates via the {@code X-Twilio-Signature} header which
 * {@link TwilioVoicemailService} verifies against the tenant's stored Twilio
 * {@code authToken} BEFORE producing any TwiML (verify-before-effect; tenant from path
 * ONLY, never the payload — the §9 external-ingress invariant, mirroring
 * {@code CalComWebhookController} / {@code StripeWebhookController}).
 *
 * <p>{@code /public/integrations/twilio/{tenantId}/voice} is reached via the
 * {@code /public/**} permitAll rule. <strong>No {@code @ConditionalOnProperty}</strong> on
 * the controller — the Stripe / Cal.com / Documenso webhook controllers are always-on under
 * {@code /public/**} and this controller follows the same convention; the module gate lives
 * on the service layer ({@code kmosf.modules.voicemail-intake}).
 */
@RestController
@RequestMapping("/public/integrations/twilio")
@ConditionalOnProperty(prefix = "kmosf.modules.voicemail-intake", name = "enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class TwilioVoiceController {

    private final TwilioVoicemailService voicemail;

    /**
     * Returns the TwiML XML that greets the caller and records + transcribes a voicemail,
     * pointing the transcription callback at {@code .../voicemail}.
     *
     * @param tenantId  the CRM tenant id (from the URL path — NEVER the payload)
     * @param signature the {@code X-Twilio-Signature} header from Twilio
     * @param form      the {@code application/x-www-form-urlencoded} voice-webhook params
     * @param exchange  the request (used to reconstruct the full signed URL)
     */
    @PostMapping(value = "/{tenantId}/voice", produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<ResponseEntity<String>> voice(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Twilio-Signature", required = false) String signature,
            ServerWebExchange exchange) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in Twilio voice webhook path", 4003, 400));
        }
        String fullUrl = TwilioVoicemailController.reconstructFullUrl(exchange);
        return exchange.getFormData()
                .flatMap(form -> voicemail.handleVoice(parsed, signature, fullUrl, form))
                .map(twiml -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .body(twiml));
    }
}
