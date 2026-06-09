package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — sends the callback opt-in SMS to a caller after a home-services
 * voicemail is captured. A {@code @PostConstruct} subscriber on the shipped
 * {@link DomainEventType#VOICEMAIL_LEAD_CREATED} event (the {@code SwitchboardDeflectionRecorder} /
 * {@code TierRoutingService} subscriber mirror).
 *
 * <h2>The reused voicemail core stays byte-unchanged (the headline)</h2>
 * {@code TwilioVoicemailService} already publishes {@code VOICEMAIL_LEAD_CREATED} (payload
 * {@code {callSid, contactId, activityId}}) after the Contact + Activity are durable — so the callback
 * offer hooks off it with <strong>no seam</strong>; {@code TwilioVoicemailService} is empty-diff vs
 * {@code main}. The event fires for EVERY voicemail vertical (mole/NMM too), so this subscriber scopes to
 * <strong>home</strong> by requiring a {@code ResponderConfig(vertical="home")} for the tenant before
 * sending — a mole/NMM lead is a clean no-op (NMM byte-equivalent).
 *
 * <h2>Default-OFF — no live caller SMS in CI / any default run (§7)</h2>
 * The bean is created only when {@code kmosf.modules.home-callback-offer.enabled=true} (matchIfMissing
 * <strong>false</strong>, the {@code ArAgingSweepJob} / {@code NurtureRunner} comms-runner precedent), so
 * absent the flag this subscriber does not exist and no offer is ever sent. The rest of T5 (the handler +
 * read endpoints + analytics) runs on the both-modules gate; only the outbound caller send carries this
 * extra default-OFF gate. Going live also needs A2P 10DLC for the caller-facing SMS (a separate human
 * action — never the loop).
 *
 * <h2>Idempotent — one offer per CallSid (ledger-insert-FIRST, never {@code switchIfEmpty(send)})</h2>
 * A {@link CallbackOfferLog} row is saved BEFORE the SMS under the unique {@code tenant_callsid_idx}; a
 * concurrent / re-fired event for the same CallSid hits the index → {@code DuplicateKeyException} →
 * {@code Mono.empty()} = zero duplicate offer. The row is also the funnel's OFFERED counter.
 */
@Slf4j
public class CallbackOfferSubscriber {

    private final DomainEventPublisher events;
    private final ResponderConfigRepository responderConfigs;
    private final CallbackConfigRepository callbackConfigs;
    private final CallbackOfferLogRepository offerLogs;
    private final CallbackFunnelLogRepository funnelLogs;
    private final ContactRepository contacts;
    private final TwilioSmsService twilioSmsService;

    public CallbackOfferSubscriber(DomainEventPublisher events,
                                   ResponderConfigRepository responderConfigs,
                                   CallbackConfigRepository callbackConfigs,
                                   CallbackOfferLogRepository offerLogs,
                                   CallbackFunnelLogRepository funnelLogs,
                                   ContactRepository contacts,
                                   TwilioSmsService twilioSmsService) {
        this.events = events;
        this.responderConfigs = responderConfigs;
        this.callbackConfigs = callbackConfigs;
        this.offerLogs = offerLogs;
        this.funnelLogs = funnelLogs;
        this.contacts = contacts;
        this.twilioSmsService = twilioSmsService;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.VOICEMAIL_LEAD_CREATED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.warn("Callback offer subscriber: error on VOICEMAIL_LEAD_CREATED for "
                                    + "tenant {} (ignored): {}", e.tenantId(), err.getMessage());
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — send the callback opt-in SMS for a home-services voicemail lead, iff the
     * tenant is a home-callback tenant ({@code ResponderConfig(vertical="home")}) and the offer has not
     * already been sent for this CallSid. A non-home tenant (mole/NMM) is a clean no-op.
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        String callSid = asString(payload.get("callSid"));
        UUID contactId = asUuid(payload.get("contactId"));
        if (tenantId == null || callSid == null || contactId == null) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        return isHomeVertical(tenantId)
                .flatMap(home -> {
                    if (!home) {
                        return Mono.empty(); // mole / NMM / non-home tenant — no offer (byte-equivalent)
                    }
                    return sendOffer(tenantId, callSid, contactId);
                })
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /** True iff the tenant has a {@code ResponderConfig(vertical="home")} (the home-callback scope). */
    private Mono<Boolean> isHomeVertical(UUID tenantId) {
        return responderConfigs.findByTenantId(tenantId)
                .map(cfg -> CallbackIntents.VERTICAL.equalsIgnoreCase(cfg.getVertical()))
                .defaultIfEmpty(false);
    }

    /**
     * Ledger-insert-FIRST then send (never {@code switchIfEmpty(send)}): save the {@link CallbackOfferLog}
     * under the unique {@code tenant_callsid_idx} (a duplicate → {@code Mono.empty()} = zero second offer),
     * then resolve the caller phone, send the opt-in SMS (best-effort), and record the OFFERED funnel row.
     */
    private Mono<Void> sendOffer(UUID tenantId, String callSid, UUID contactId) {
        CallbackOfferLog ledger = CallbackOfferLog.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .callSid(callSid)
                .contactId(contactId)
                .offeredAt(Instant.now())
                .build();
        return offerLogs.save(ledger)
                .onErrorResume(DuplicateKeyException.class, dk -> {
                    log.debug("Callback offer already sent for CallSid {} (tenant {}) — no second offer",
                            callSid, tenantId);
                    return Mono.empty();
                })
                .flatMap(saved -> {
                    if (saved == null) {
                        return Mono.empty();
                    }
                    return resolveOfferMessage(tenantId)
                            .flatMap(message -> sendSms(tenantId, contactId, message))
                            .then(recordOfferedFunnel(tenantId, callSid));
                });
    }

    /** Resolve the per-tenant offer copy, falling back to the generic default. */
    private Mono<String> resolveOfferMessage(UUID tenantId) {
        return callbackConfigs.findByTenantId(tenantId)
                .map(cfg -> (cfg.getOfferMessage() == null || cfg.getOfferMessage().isBlank())
                        ? CallbackCopy.DEFAULT_OFFER_MESSAGE : cfg.getOfferMessage().trim())
                .defaultIfEmpty(CallbackCopy.DEFAULT_OFFER_MESSAGE);
    }

    /** Resolve the caller's phone from the Contact, then send the opt-in SMS (best-effort). */
    private Mono<Void> sendSms(UUID tenantId, UUID contactId, String message) {
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .flatMap(contact -> {
                    String phone = firstPhone(contact);
                    if (phone == null) {
                        log.debug("Callback offer: contact {} (tenant {}) has no phone — skipping send",
                                contactId, tenantId);
                        return Mono.empty();
                    }
                    SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                            .to(new PhoneContact(phone))
                            .body(message)
                            .build();
                    return twilioSmsService.sendSms(req)
                            .onErrorResume(e -> {
                                log.warn("Callback offer SMS failed for tenant {} (best-effort, "
                                        + "ignored): {}", tenantId, e.getMessage());
                                return Mono.just(false);
                            })
                            .then();
                });
    }

    /** Record the OFFERED funnel row (best-effort — analytics never break the offer). */
    private Mono<Void> recordOfferedFunnel(UUID tenantId, String callSid) {
        return funnelLogs.save(CallbackFunnelLog.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .stage(CallbackFunnelStage.OFFERED)
                        .callSid(callSid)
                        .occurredAt(Instant.now())
                        .build())
                .then()
                .onErrorResume(e -> {
                    log.warn("Callback OFFERED funnel write failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                });
    }

    private static String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null) {
            return null;
        }
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number().trim();
            }
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static UUID asUuid(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof UUID u) {
            return u;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
