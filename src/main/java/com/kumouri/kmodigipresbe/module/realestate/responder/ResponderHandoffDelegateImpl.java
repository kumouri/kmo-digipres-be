package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.service.responder.DefaultHandoffIntentHandler;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the {@link ResponderHandoffDelegate} implementation that wraps
 * the <strong>UNCHANGED</strong> E2 {@code DefaultHandoffIntentHandler}.
 *
 * <h2>What it reuses (no new handoff copy, no new notify path)</h2>
 * {@code DefaultHandoffIntentHandler.handle(...)} best-effort notifies staff (email + SMS to the per-tenant
 * {@code IntegrationConnection(twilio).config.notifyEmail}/{@code notifyPhone} — NOT hardcoded) and returns
 * a generic "a team member will follow up" reply (vetted + static — no listing/demographic content, so
 * fair-housing-safe by construction). This delegate runs that handler under the concierge router's already-
 * established synthetic tenant context, then best-effort sends the returned reply to the buyer over
 * {@code TwilioSmsService}. So an off-listing / unknown-intent inbound that the grounded concierge could not
 * answer gets the same human-handoff experience as a generic responder message — instead of dying silently.
 *
 * <h2>Per-tenant policy</h2>
 * {@link Reason#NO_LISTING} always delegates. {@link Reason#HANDOFF} delegates only when the tenant's
 * {@link MidnightResponderConfig#isDelegateHandoffToResponder()} flag is set (default off ⇒ the RE-1
 * handoff reply stays byte-identical; the delegate no-ops). The policy check lives here (the delegate holds
 * the config repo) so the concierge-router seam needs no T3 config dependency.
 *
 * <h2>Best-effort by contract</h2>
 * Never throws; swallows the reply-send failure (the concierge router is webhook-reachable — a 500 here
 * would fail the inbound webhook). Returns {@code true} when the reply was sent, else {@code false} (policy
 * declined, blank reply, or a swallowed failure) — the router uses this only for logging. The conversation
 * context handed to the handler is a transient (non-persisted) {@link ConversationState} the default
 * handler does not read.
 */
@Slf4j
public class ResponderHandoffDelegateImpl implements ResponderHandoffDelegate {

    private final DefaultHandoffIntentHandler defaultHandoff;
    private final TwilioSmsService twilioSmsService;
    private final MidnightResponderConfigRepository configs;

    public ResponderHandoffDelegateImpl(DefaultHandoffIntentHandler defaultHandoff,
                                        TwilioSmsService twilioSmsService,
                                        MidnightResponderConfigRepository configs) {
        this.defaultHandoff = defaultHandoff;
        this.twilioSmsService = twilioSmsService;
        this.configs = configs;
    }

    @Override
    public Mono<Boolean> delegate(UUID tenantId, String from, String to, String body, Reason reason) {
        if (from == null || from.isBlank()) {
            return Mono.just(false);
        }
        // NO_LISTING always delegates; HANDOFF delegates only when the per-tenant flag is set.
        if (reason == Reason.HANDOFF) {
            return configs.findByTenantId(tenantId)
                    .map(MidnightResponderConfig::isDelegateHandoffToResponder)
                    .defaultIfEmpty(false)
                    .flatMap(allowed -> allowed
                            ? runHandoff(tenantId, from, to, body)
                            : Mono.just(false))
                    .onErrorResume(e -> {
                        log.warn("T3 responder-handoff: HANDOFF policy check failed (best-effort) for {}: {}",
                                from, e.toString());
                        return Mono.just(false);
                    });
        }
        return runHandoff(tenantId, from, to, body);
    }

    private Mono<Boolean> runHandoff(UUID tenantId, String from, String to, String body) {
        // A transient conversation-state row the default handler does not read (it only needs the envelope).
        ConversationState transientState = ConversationState.builder()
                .tenantId(tenantId)
                .phone(from)
                .vertical("realestate")
                .build();
        IntentHandler.HandlerContext hctx = new IntentHandler.HandlerContext(
                tenantId, from, to, body, IntentClassification.unknown(), transientState);
        return defaultHandoff.handle(hctx)
                .flatMap(result -> {
                    String reply = result.replyText();
                    if (reply == null || reply.isBlank()) {
                        return Mono.just(true); // staff was still notified by the handler; no reply to send
                    }
                    return twilioSmsService.sendSms(SmsCommunicationRequest.builder()
                                    .to(PhoneContact.builder().e164(from).build())
                                    .body(reply)
                                    .build())
                            .thenReturn(true)
                            .onErrorResume(e -> {
                                log.warn("T3 responder-handoff: reply SMS to {} failed (best-effort): {}",
                                        from, e.toString());
                                return Mono.just(false);
                            });
                })
                .onErrorResume(e -> {
                    log.warn("T3 responder-handoff: delegation failed (best-effort, ignored) for {}: {}",
                            from, e.toString());
                    return Mono.just(false);
                });
    }
}
