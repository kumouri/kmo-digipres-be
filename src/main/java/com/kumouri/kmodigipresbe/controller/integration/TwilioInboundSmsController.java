package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.twilio.InboundSmsModuleEnabledCondition;
import com.kumouri.kmodigipresbe.integration.twilio.InboundSmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * ChairFill CF-3 — unauthenticated Twilio <em>inbound-SMS</em> webhook (the gap-fill "first YES claims
 * the slot" + TCPA STOP route). The net-new inbound-SMS surface mirroring {@link TwilioVoicemailController}
 * (HS shipped only voicemail/voice webhooks — no inbound-SMS route existed on {@code main}).
 *
 * <p>Twilio POSTs the inbound message here as {@code application/x-www-form-urlencoded} (params
 * {@code From, To, Body, MessageSid, ...}). {@link InboundSmsService} verifies the
 * {@code X-Twilio-Signature} against the tenant's stored Twilio {@code authToken} BEFORE any effect
 * (reused {@code 4000-4003}), then classifies the body: an affirmative ("YES") drives the atomic slot
 * claim; a STOP sets the {@code sms-opt-out} consent tag. Tenant id is resolved from the URL path
 * <strong>only</strong> — never the payload (§9 external-ingress invariant).
 *
 * <p>Gated by {@link InboundSmsModuleEnabledCondition} (chairfill OR realestate enabled) so the controller
 * is absent from the generated OpenAPI spec when neither module provides the inbound-SMS service (the
 * {@code NoShowRiskController} / waitlist-widget posture — unlike the always-on {@code matchIfMissing=true}
 * voicemail webhook). The {@code InboundSmsService} bean is contributed by ChairFill (CF-3) and/or by the
 * Real Estate Concierge module (RE-1, for a pure-realestate deployment where chairfill is off) — so the
 * single inbound webhook + signature boundary is reused across both, exactly as the RE-1 "REUSE/EXTEND
 * CF-3" directive requires. {@code /public/integrations/twilio/{tenantId}/sms} is reached via the
 * {@code /public/**} permitAll rule. Returns 200 on success (Twilio treats any 2xx as acknowledged).
 */
@RestController
@RequestMapping("/public/integrations/twilio")
@Conditional(InboundSmsModuleEnabledCondition.class)
@RequiredArgsConstructor
public class TwilioInboundSmsController {

    private final InboundSmsService inboundSms;

    @PostMapping("/{tenantId}/sms")
    public Mono<Void> sms(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Twilio-Signature", required = false) String signature,
            ServerWebExchange exchange) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in Twilio inbound-SMS webhook path", 4003, 400));
        }
        // Reuse the voicemail controller's proxy-aware URL reconstruction (same package, same canonical
        // URL the Twilio signature is computed over — Cloudflare Tunnel + Caddy honored).
        String fullUrl = TwilioVoicemailController.reconstructFullUrl(exchange);
        return exchange.getFormData()
                .flatMap(form -> inboundSms.handleInboundSms(parsed, signature, fullUrl, form))
                .then();
    }
}
