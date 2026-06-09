package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * T4 — the thin writer/reader for the PHI-free {@link SwitchboardDeflectionLog} analytics ledger. The
 * handlers ({@link LogisticsIntentHandler}, {@link ClinicalTripwireHandler}) and the
 * {@link SwitchboardDeflectionRecorder} call {@link #record} to append a category row; the
 * {@code SwitchboardController} calls {@link #stats} to read the per-category counts.
 *
 * <p>{@link #record} is <strong>best-effort</strong>: a deflection-analytics write must never fail the
 * inbound handling (the patient was already answered / handed off). A write error degrades to a logged
 * no-op. The row carries only {@code (tenantId, category, occurredAt)} — never any content.
 */
@Slf4j
public class SwitchboardDeflectionService {

    private final SwitchboardDeflectionLogRepository logs;

    public SwitchboardDeflectionService(SwitchboardDeflectionLogRepository logs) {
        this.logs = logs;
    }

    /**
     * Append one PHI-free deflection row (best-effort). Runs under whatever context the caller is in
     * (the synthetic responder tenant context). A failure is swallowed — analytics never break handling.
     */
    public Mono<Void> record(UUID tenantId, SwitchboardDeflectionCategory category) {
        return logs.save(SwitchboardDeflectionLog.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .category(category)
                        .occurredAt(Instant.now())
                        .build())
                .doOnSuccess(saved -> log.debug("Switchboard deflection recorded: tenant={} category={}",
                        tenantId, category))
                .onErrorResume(e -> {
                    log.warn("Switchboard deflection record failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /** Read the all-time per-category deflection counts for this tenant. */
    public Mono<SwitchboardDeflectionStats> stats(UUID tenantId) {
        return Mono.zip(
                        logs.countByTenantIdAndCategory(tenantId, SwitchboardDeflectionCategory.LOGISTICS)
                                .defaultIfEmpty(0L),
                        logs.countByTenantIdAndCategory(tenantId, SwitchboardDeflectionCategory.TRIPWIRE)
                                .defaultIfEmpty(0L),
                        logs.countByTenantIdAndCategory(tenantId, SwitchboardDeflectionCategory.HANDOFF)
                                .defaultIfEmpty(0L))
                .map(t -> SwitchboardDeflectionStats.of(t.getT1(), t.getT2(), t.getT3()));
    }
}
