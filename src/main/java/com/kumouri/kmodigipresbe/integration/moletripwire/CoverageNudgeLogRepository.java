package com.kumouri.kmodigipresbe.integration.moletripwire;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the {@link CoverageNudgeLog} idempotency ledger (Phase 3 — coverage-window nudge).
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders. The probe
 * is used as an <strong>explicit-boolean</strong> seen/not-seen branch in {@code CoverageNudgeJob}
 * (never {@code switchIfEmpty(send)}); the unique {@code tenant_project_period_idx} backstops the
 * ledger-insert-FIRST against a concurrent re-run.
 */
public interface CoverageNudgeLogRepository
        extends TenantScopedReactiveMongoRepository<CoverageNudgeLog, UUID> {

    Mono<CoverageNudgeLog> findByTenantIdAndProjectIdAndPeriodKey(
            UUID tenantId, UUID projectId, String periodKey);
}
