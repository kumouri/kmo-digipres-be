package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T7 — repository for the PHI-free {@link RescheduleFillLog} fill-funnel ledger.
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the subscriber +
 * materializer write under a synthetic context, outside a request). It backs the per-stage fill counts.
 */
public interface RescheduleFillLogRepository
        extends TenantScopedReactiveMongoRepository<RescheduleFillLog, UUID> {

    /** All-time count of rows for this tenant + funnel stage. */
    Mono<Long> countByTenantIdAndEvent(UUID tenantId, RescheduleFillEvent event);
}
