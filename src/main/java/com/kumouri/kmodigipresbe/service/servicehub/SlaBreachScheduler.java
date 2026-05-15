package com.kumouri.kmodigipresbe.service.servicehub;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans every minute for tickets whose resolution SLA has been breached.
 * Sets {@code slaBreachedAt} and publishes {@link DomainEventType#SLA_BREACHED}
 * so workflow rules can escalate or notify.
 *
 * <p>Runs across all tenants (no tenant filter on the scan query) with a
 * synthetic tenant context per ticket so {@code TenantStampingCallback} stays
 * consistent with other scheduled services (e.g., {@code SlaBreachScheduler}
 * mirrors the {@code ServiceAgreementSchedulerService} pattern from Phase 10b).
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "kmosf.service-hub.sla-breach-scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class SlaBreachScheduler {

    private static final List<TicketStatus> TERMINAL = List.of(
            TicketStatus.RESOLVED, TicketStatus.CLOSED);

    private final TicketRepository tickets;
    private final DomainEventPublisher events;

    @Scheduled(fixedRateString = "${kmosf.service-hub.sla-breach-scan-ms:60000}",
               initialDelayString = "${kmosf.service-hub.sla-breach-initial-delay-ms:30000}")
    public void tick() {
        scanOnce()
                .onErrorResume(err -> {
                    log.error("SlaBreachScheduler tick failed: {}", err.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    /** Visible for tests — one full scan pass. */
    public Mono<Void> scanOnce() {
        Instant now = Instant.now();
        return tickets.findAllBySlaResolutionDueBeforeAndSlaBreachedAtIsNullAndStatusNotIn(now, TERMINAL)
                .flatMap(ticket -> {
                    ticket.setSlaBreachedAt(now);
                    TenantContext ctx = new TenantContext(
                            ticket.getTenantId(), null, Set.of("SLA_BREACH_SCHEDULER"));
                    return tickets.save(ticket)
                            .doOnNext(saved -> events.publish(new DomainEvent(
                                    DomainEventType.SLA_BREACHED, saved.getTenantId(), saved.getId(),
                                    Map.of("priority", saved.getPriority().name(),
                                            "subject", saved.getSubject() == null ? "" : saved.getSubject()),
                                    now)))
                            .contextWrite(TenantContextHolder.write(ctx));
                })
                .onErrorContinue((err, ticket) ->
                        log.warn("SlaBreachScheduler skipped ticket {}: {}", ticket, err.toString()))
                .then();
    }
}
