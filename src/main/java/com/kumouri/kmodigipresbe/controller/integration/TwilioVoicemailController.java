package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.twilio.voice.TwilioVoicemailService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * Unauthenticated Twilio <em>transcription/recording callback</em> webhook (Phase 1 — NMM
 * voicemail-to-lead) — the meaty one, mirroring {@code CalComWebhookController} /
 * {@code StripeWebhookController}.
 *
 * <p>Twilio POSTs the transcription result here as {@code application/x-www-form-urlencoded}
 * (params {@code CallSid, RecordingSid, RecordingUrl, From, To, TranscriptionText,
 * TranscriptionStatus}). {@link TwilioVoicemailService} verifies the
 * {@code X-Twilio-Signature} against the tenant's stored Twilio {@code authToken} BEFORE any
 * effect, then runs the idempotent ledger-first → extract → lead + Activity → notify
 * pipeline. Tenant id is resolved from the URL path <strong>only</strong> — never the payload
 * (§9 external-ingress invariant).
 *
 * <p>{@code /public/integrations/twilio/{tenantId}/voicemail} is reached via the
 * {@code /public/**} permitAll rule. No {@code @ConditionalOnProperty} on the controller —
 * the module gate is on the service layer.
 */
@RestController
@RequestMapping("/public/integrations/twilio")
@ConditionalOnProperty(prefix = "kmosf.modules.voicemail-intake", name = "enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class TwilioVoicemailController {

    private final TwilioVoicemailService voicemail;

    /**
     * Receives a Twilio transcription/recording callback. Returns 200 on success (Twilio
     * treats any 2xx as acknowledged); a duplicate {@code CallSid} re-delivery also returns
     * 200 (no-op) — the §9 #1 headline.
     *
     * @param tenantId  the CRM tenant id (from the URL path — NEVER the payload)
     * @param signature the {@code X-Twilio-Signature} header from Twilio
     * @param form      the transcription-callback form params
     * @param exchange  the request (used to reconstruct the full signed URL)
     */
    @PostMapping("/{tenantId}/voicemail")
    public Mono<Void> voicemail(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Twilio-Signature", required = false) String signature,
            @RequestBody(required = false) MultiValueMap<String, String> form,
            ServerWebExchange exchange) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in Twilio voicemail webhook path", 4003, 400));
        }
        String fullUrl = reconstructFullUrl(exchange);
        return voicemail.handleVoicemail(parsed, signature, fullUrl, form);
    }

    /**
     * Reconstructs the full request URL Twilio signed. Twilio signs the public URL it was
     * configured to POST to; behind a reverse proxy (Cloudflare Tunnel + Caddy) the BE sees
     * an internal URL, so this honors {@code X-Forwarded-Proto} / {@code X-Forwarded-Host} /
     * {@code Forwarded} headers when present (the standard proxy-aware reconstruction the
     * Twilio SDK helpers use), falling back to the request's own URI.
     *
     * <p>Package-private + static so both Twilio controllers and the Phase-1 ITs use the
     * exact same canonical URL the signature is computed over.
     */
    static String reconstructFullUrl(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        URI uri = request.getURI();
        HttpHeaders headers = request.getHeaders();

        String proto = firstHeader(headers, "X-Forwarded-Proto");
        String host = firstHeader(headers, "X-Forwarded-Host");
        if (host == null) {
            host = firstHeader(headers, HttpHeaders.HOST);
        }

        // No proxy headers → trust the request URI verbatim (local/dev/test path).
        if (host == null) {
            return uri.toString();
        }

        String scheme = proto != null ? proto : uri.getScheme();
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host).append(uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        String v = headers.getFirst(name);
        if (v == null || v.isBlank()) return null;
        // X-Forwarded-* may be a comma-separated list; the first value is the client-facing one.
        int comma = v.indexOf(',');
        return (comma >= 0 ? v.substring(0, comma) : v).trim();
    }
}
