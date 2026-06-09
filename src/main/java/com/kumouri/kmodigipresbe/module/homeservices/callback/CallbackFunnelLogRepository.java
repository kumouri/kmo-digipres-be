package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T5 — repository for the {@link CallbackFunnelLog} recovery-funnel analytics ledger.
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders). These back the
 * per-stage funnel counts.
 */
public interface CallbackFunnelLogRepository
        extends TenantScopedReactiveMongoRepository<CallbackFunnelLog, UUID> {

    /** All-time count of rows for this tenant + funnel stage. */
    Mono<Long> countByTenantIdAndStage(UUID tenantId, CallbackFunnelStage stage);
}
