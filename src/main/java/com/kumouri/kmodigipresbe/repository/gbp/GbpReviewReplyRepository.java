package com.kumouri.kmodigipresbe.repository.gbp;

import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the GBP review-reply ledger/draft record (NMM GBP review-reply automation).
 *
 * <p>{@link #findByTenantIdAndReviewId} is the <strong>explicit-boolean probe</strong> used by
 * {@code GbpReviewPoller}: mapped to a boolean and branched ({@code seen ? skip : draftAndRecord})
 * — <strong>NEVER {@code switchIfEmpty(process)}</strong> (the documented §9 trap). The unique
 * {@code tenant_review_idx} is the hard backstop for a concurrent re-poll (mirrors
 * {@code CalComWebhookEventRepository} / {@code TwilioVoicemailEventRepository}).
 *
 * <p>{@link #findByTenantIdAndStatusOrderByReceivedAtDesc} and {@link #findByTenantIdAndId} carry an
 * <strong>explicit {@code tenantId} predicate</strong> — the {@link TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders.
 */
public interface GbpReviewReplyRepository
        extends TenantScopedReactiveMongoRepository<GbpReviewReply, UUID> {

    Mono<GbpReviewReply> findByTenantIdAndReviewId(UUID tenantId, String reviewId);

    Flux<GbpReviewReply> findByTenantIdAndStatusOrderByReceivedAtDesc(
            UUID tenantId, GbpReviewReply.Status status);

    Mono<GbpReviewReply> findByTenantIdAndId(UUID tenantId, UUID id);
}
