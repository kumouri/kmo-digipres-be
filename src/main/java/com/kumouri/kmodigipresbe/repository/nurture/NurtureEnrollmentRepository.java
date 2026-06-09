package com.kumouri.kmodigipresbe.repository.nurture;

import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link NurtureEnrollment} (E1 — Nurture / Cadence Engine).
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders. The
 * {@code findByTenantIdAndCampaignIdAndContactId} probe is used as an <strong>explicit-boolean</strong>
 * find-or-enroll branch in {@code NurtureSegmentationService} (never {@code switchIfEmpty(create)});
 * the unique {@code tenant_campaign_contact_idx} backstops the concurrent enroll race.
 */
public interface NurtureEnrollmentRepository
        extends TenantScopedReactiveMongoRepository<NurtureEnrollment, UUID> {

    Mono<NurtureEnrollment> findByTenantIdAndId(UUID tenantId, UUID id);

    Mono<NurtureEnrollment> findByTenantIdAndCampaignIdAndContactId(
            UUID tenantId, UUID campaignId, UUID contactId);

    Flux<NurtureEnrollment> findAllByTenantIdAndCampaignId(UUID tenantId, UUID campaignId);

    /** All non-terminal enrollments for a contact (newest first) — the reply-by-phone resolver. */
    Flux<NurtureEnrollment> findAllByTenantIdAndContactIdAndStatusInOrderByEnrolledAtDesc(
            UUID tenantId, UUID contactId, java.util.Collection<NurtureEnrollmentStatus> statuses);
}
