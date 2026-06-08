package com.kumouri.kmodigipresbe.module.frontdesk.automation;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the {@link RecallLog} FD-2 recall/recare idempotency ledger.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the
 * {@code RecallDetectorJob} runs OUTSIDE a request context (it establishes a synthetic
 * {@code TenantContext} per tenant). The unique {@code tenant_contact_period_idx} backstops the
 * ledger-insert-FIRST against a concurrent re-run; {@link #findByTenantIdAndContactIdAndPeriodKey} backs
 * the explicit-boolean not-yet-seen probe (the {@code CoverageNudgeLogRepository} posture).
 */
public interface RecallLogRepository
        extends TenantScopedReactiveMongoRepository<RecallLog, UUID> {

    /** The explicit-boolean idempotency probe for a (tenant, contact, period). */
    Mono<RecallLog> findByTenantIdAndContactIdAndPeriodKey(UUID tenantId, UUID contactId, String periodKey);

    /**
     * All recall-ledger rows for a tenant in one period (ISO week). Backs the FD-5a recall-board read's
     * {@code nudgedThisPeriod} enrichment — the board marks a recall-due contact whose sweep already
     * fired this period so a staffer does not manually double-nudge. Explicit {@code tenantId} predicate
     * (the marker does not auto-scope derived finders).
     */
    Flux<RecallLog> findByTenantIdAndPeriodKey(UUID tenantId, String periodKey);
}
