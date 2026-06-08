package com.kumouri.kmodigipresbe.module.chairfill.automation;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for the {@link ReminderLog} CF-2 prevention idempotency + frequency-cap ledger.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the
 * {@code RiskTieredPreventionService} runs OUTSIDE a request context (it establishes a synthetic
 * {@code TenantContext} per event). The unique {@code tenant_booking_idx} backstops the
 * ledger-insert-FIRST against a concurrent re-fire; {@link #countByTenantIdAndContactIdAndSentAtAfter}
 * backs the per-contact rolling frequency cap.
 */
public interface ReminderLogRepository
        extends TenantScopedReactiveMongoRepository<ReminderLog, UUID> {

    /** Count of prevention actions for this contact since {@code since} — the frequency-cap probe. */
    Mono<Long> countByTenantIdAndContactIdAndSentAtAfter(UUID tenantId, UUID contactId, Instant since);
}
