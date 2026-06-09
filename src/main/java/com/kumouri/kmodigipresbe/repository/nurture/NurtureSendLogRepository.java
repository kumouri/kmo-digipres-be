package com.kumouri.kmodigipresbe.repository.nurture;

import com.kumouri.kmodigipresbe.model.nurture.NurtureSendLog;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for the {@link NurtureSendLog} idempotency ledger (E1 — Nurture / Cadence Engine).
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders.
 * {@code findByTenantIdAndEnrollmentIdAndStepIndex} is the per-(enrollment, step) probe; the unique
 * {@code tenant_enrollment_step_idx} backstops the ledger-insert-FIRST against a concurrent tick.
 * {@code countByTenantIdAndContactIdAndSentAtAfter} backs the TCPA rolling frequency cap.
 */
public interface NurtureSendLogRepository
        extends TenantScopedReactiveMongoRepository<NurtureSendLog, UUID> {

    Mono<NurtureSendLog> findByTenantIdAndEnrollmentIdAndStepIndex(
            UUID tenantId, UUID enrollmentId, int stepIndex);

    Mono<Long> countByTenantIdAndContactIdAndSentAtAfter(
            UUID tenantId, UUID contactId, Instant after);

    Flux<NurtureSendLog> findAllByTenantIdAndEnrollmentId(UUID tenantId, UUID enrollmentId);

    Flux<NurtureSendLog> findAllByTenantId(UUID tenantId);
}
