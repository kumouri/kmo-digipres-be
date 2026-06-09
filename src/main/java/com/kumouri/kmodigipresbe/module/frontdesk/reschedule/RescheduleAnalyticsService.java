package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * T7 — the thin writer/reader for the PHI-free {@link RescheduleFillLog} fill-funnel ledger (the
 * {@code SwitchboardDeflectionService} posture). The subscriber + materializer call {@link #record} to
 * append a stage row; the {@code RescheduleController} calls {@link #stats} to read the per-stage counts.
 *
 * <p>{@link #record} is <strong>best-effort</strong>: an analytics write must never fail the gap-fill /
 * claim path (the slot was already offered / filled). A write error degrades to a logged no-op. The row
 * carries only {@code (tenantId, event, occurredAt)} — never any content (PHI-free, fence F1).
 */
@Slf4j
public class RescheduleAnalyticsService {

    private final RescheduleFillLogRepository logs;

    public RescheduleAnalyticsService(RescheduleFillLogRepository logs) {
        this.logs = logs;
    }

    /**
     * Append one PHI-free fill-funnel row (best-effort). Runs under whatever context the caller is in (the
     * engine's synthetic tenant context). A failure is swallowed — analytics never break the funnel.
     */
    public Mono<Void> record(UUID tenantId, RescheduleFillEvent event) {
        return logs.save(RescheduleFillLog.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .event(event)
                        .occurredAt(Instant.now())
                        .build())
                .doOnSuccess(saved -> log.debug("RescheduleFlow fill-event recorded: tenant={} event={}",
                        tenantId, event))
                .onErrorResume(e -> {
                    log.warn("RescheduleFlow fill-event record failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /** Read the all-time per-stage fill counts for this tenant (cancellation / offer / claim / filled). */
    public Mono<RescheduleFillStats> stats(UUID tenantId) {
        return Mono.zip(
                        logs.countByTenantIdAndEvent(tenantId, RescheduleFillEvent.CANCELLATION)
                                .defaultIfEmpty(0L),
                        logs.countByTenantIdAndEvent(tenantId, RescheduleFillEvent.OFFER)
                                .defaultIfEmpty(0L),
                        logs.countByTenantIdAndEvent(tenantId, RescheduleFillEvent.CLAIM)
                                .defaultIfEmpty(0L),
                        logs.countByTenantIdAndEvent(tenantId, RescheduleFillEvent.FILLED)
                                .defaultIfEmpty(0L))
                .map(t -> RescheduleFillStats.of(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }
}
