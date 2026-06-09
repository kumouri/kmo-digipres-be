package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * T4 — records a {@link SwitchboardDeflectionCategory#HANDOFF} deflection row whenever the E2
 * {@code DefaultHandoffIntentHandler} hands off an inbound message for a <strong>health-vertical</strong>
 * tenant (an UNKNOWN / unmatched message that neither the logistics handler nor the tripwire claimed).
 * A {@code @PostConstruct} subscriber on {@link DomainEventType#RESPONDER_HANDED_OFF} — the
 * {@code TierRoutingService} subscriber mirror.
 *
 * <h2>Why a subscriber (vs an inline write)</h2>
 * The {@code LOGISTICS} and {@code TRIPWIRE} categories are written inline by the T4 handlers themselves.
 * The handoff is performed by the E2 {@code DefaultHandoffIntentHandler}, which T4 does not own and must
 * not edit — so the handoff count is captured by listening to its advisory {@code RESPONDER_HANDED_OFF}
 * event. The event fires for ALL verticals (realestate too), so this recorder <strong>scopes to health</strong>
 * by checking the tenant has a {@code ResponderConfig(vertical="health")} before recording — a realestate
 * handoff is ignored. Best-effort: the {@code RESPONDER_HANDED_OFF} payload carries no PHI (only
 * {@code {phone, intent}}) and this records only {@code (tenantId, HANDOFF, now)} — never any content.
 */
@Slf4j
public class SwitchboardDeflectionRecorder {

    private final DomainEventPublisher events;
    private final ResponderConfigRepository responderConfigs;
    private final SwitchboardDeflectionService deflection;

    public SwitchboardDeflectionRecorder(DomainEventPublisher events,
                                         ResponderConfigRepository responderConfigs,
                                         SwitchboardDeflectionService deflection) {
        this.events = events;
        this.responderConfigs = responderConfigs;
        this.deflection = deflection;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.RESPONDER_HANDED_OFF.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.warn("Switchboard deflection recorder: error on RESPONDER_HANDED_OFF "
                                    + "for tenant {} (ignored): {}", e.tenantId(), err.getMessage());
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — record a HANDOFF deflection row iff the tenant is a health-vertical
     * Switchboard tenant. A non-health tenant (e.g. realestate Midnight Responder) is a clean no-op.
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        if (tenantId == null) {
            return Mono.empty();
        }
        return responderConfigs.findByTenantId(tenantId)
                .filter(cfg -> SwitchboardIntents.VERTICAL.equalsIgnoreCase(cfg.getVertical()))
                .flatMap(cfg -> deflection.record(tenantId, SwitchboardDeflectionCategory.HANDOFF))
                .then();
    }
}
