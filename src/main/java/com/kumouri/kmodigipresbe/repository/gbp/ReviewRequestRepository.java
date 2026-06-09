package com.kumouri.kmodigipresbe.repository.gbp;

import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for the E3 {@link ReviewRequest} record (Review Engine — review-request delivery).
 *
 * <p>Every derived finder carries an <strong>explicit {@code tenantId} predicate</strong> — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and both the
 * {@code ReviewRequestService} subscriber and the {@code ReviewRequestSenderJob} run OUTSIDE a request
 * context (each establishes a synthetic {@code TenantContext} per event/sweep).
 *
 * <ul>
 *   <li>{@link #findByTenantIdAndSubjectTypeAndSubjectIdAndContactId} — the creation
 *       <strong>explicit-boolean probe</strong> over the unique {@code tenant_subject_contact_idx}
 *       (mapped to a boolean and branched; <strong>never {@code switchIfEmpty(create)}</strong>).</li>
 *   <li>{@link #findByTenantIdAndStatusAndDueAtBefore} — the sender's due-request query.</li>
 *   <li>{@link #countByTenantIdAndContactIdAndStatusAndSentAtAfter} — the per-contact rolling
 *       frequency cap (the {@code ReminderLogRepository.countBy…SentAtAfter} precedent).</li>
 *   <li>{@link #findByTenantIdAndSubjectTypeAndSubjectId} / {@link #findByTenantId} — insights rollups.</li>
 * </ul>
 */
public interface ReviewRequestRepository
        extends TenantScopedReactiveMongoRepository<ReviewRequest, UUID> {

    Mono<ReviewRequest> findByTenantIdAndSubjectTypeAndSubjectIdAndContactId(
            UUID tenantId, ReviewSubjectType subjectType, UUID subjectId, UUID contactId);

    Flux<ReviewRequest> findByTenantIdAndStatusAndDueAtBefore(
            UUID tenantId, ReviewRequest.Status status, Instant dueBefore);

    /** Count of SENT requests for this contact since {@code since} — the frequency-cap probe. */
    Mono<Long> countByTenantIdAndContactIdAndStatusAndSentAtAfter(
            UUID tenantId, UUID contactId, ReviewRequest.Status status, Instant since);

    Flux<ReviewRequest> findByTenantIdAndSubjectTypeAndSubjectId(
            UUID tenantId, ReviewSubjectType subjectType, UUID subjectId);

    Flux<ReviewRequest> findByTenantId(UUID tenantId);
}
