package com.kumouri.kmodigipresbe.module.frontdesk.automation;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for the {@link ConfirmationLog} FD-2 risk-tiered-confirmation idempotency + frequency-cap
 * ledger.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the
 * {@code FrontDeskConfirmationService} runs OUTSIDE a request context (it establishes a synthetic
 * {@code TenantContext} per event). The unique {@code tenant_appointment_idx} backstops the
 * ledger-insert-FIRST against a concurrent re-fire; {@link #countByTenantIdAndContactIdAndSentAtAfter}
 * backs the per-contact rolling frequency cap (mirror of the chairfill
 * {@code ReminderLogRepository}).
 */
public interface ConfirmationLogRepository
        extends TenantScopedReactiveMongoRepository<ConfirmationLog, UUID> {

    /** Count of confirmation actions for this contact since {@code since} — the frequency-cap probe. */
    Mono<Long> countByTenantIdAndContactIdAndSentAtAfter(UUID tenantId, UUID contactId, Instant since);
}
