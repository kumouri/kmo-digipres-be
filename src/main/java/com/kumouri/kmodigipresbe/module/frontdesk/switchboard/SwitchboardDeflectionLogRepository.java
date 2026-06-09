package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * T4 — repository for the {@link SwitchboardDeflectionLog} analytics ledger.
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the recorder +
 * handlers run under a synthetic context). These back the per-category deflection counts.
 */
public interface SwitchboardDeflectionLogRepository
        extends TenantScopedReactiveMongoRepository<SwitchboardDeflectionLog, UUID> {

    /** All-time count of rows for this tenant + category. */
    Mono<Long> countByTenantIdAndCategory(UUID tenantId, SwitchboardDeflectionCategory category);

    /** Rolling-window count of rows for this tenant + category since {@code after}. */
    Mono<Long> countByTenantIdAndCategoryAndOccurredAtAfter(
            UUID tenantId, SwitchboardDeflectionCategory category, Instant after);
}
