package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.response.ReviewInsights;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Aggregates the E3 Review Engine read model (per-entity insights). Computes, for the whole tenant or a
 * single {@code (subjectType, subjectId)} entity, the review-content metrics (count, average rating,
 * sentiment breakdown — from {@code GbpReviewReply}) and the request funnel (requests sent vs a bounded
 * response proxy — from {@code ReviewRequest}). Generic: keyed by {@link ReviewSubjectType}, no
 * salon/home hardcoding.
 *
 * <p>Reads the tenant from the active (authenticated, admin-gated) reactive context — the insights
 * endpoints run in a real staff request (the {@code GbpReviewReplyAdminService} precedent). All
 * repository finders carry an explicit {@code tenantId} predicate.
 *
 * <h2>Attribution honesty (see {@link ReviewInsights})</h2>
 * Google reviews carry no per-staff / per-job attribution, so the review-content block is inherently
 * tenant-level and is reported the same on both endpoints; the request funnel is the genuinely
 * entity-attributable part and is scoped to the subject on the per-entity endpoint. The response proxy
 * is {@code min(reviewCount, requestsSent)} — bounded so it is never > sent (GBP reviews are not
 * request-correlated; a precise correlation would need Google to echo a request id, which it does not).
 */
@Service
@RequiredArgsConstructor
public class ReviewInsightsService {

    private final GbpReviewReplyRepository reviewReplies;
    private final ReviewRequestRepository reviewRequests;

    /** Whole-tenant rollup: review-content over all reviews + the request funnel over all SENT requests. */
    public Mono<ReviewInsights> insightsForTenant() {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID tenantId = ctx.tenantId();
            Mono<List<GbpReviewReply>> reviews = reviewReplies.findByTenantId(tenantId).collectList();
            Mono<List<ReviewRequest>> requests = reviewRequests.findByTenantId(tenantId).collectList();
            return Mono.zip(reviews, requests)
                    .map(t -> build(null, null, t.getT1(), t.getT2()));
        });
    }

    /**
     * Per-entity rollup: the request funnel is scoped to {@code (subjectType, subjectId)}; the
     * review-content block is the tenant-wide review health (see class doc).
     */
    public Mono<ReviewInsights> insightsForSubject(ReviewSubjectType subjectType, UUID subjectId) {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID tenantId = ctx.tenantId();
            Mono<List<GbpReviewReply>> reviews = reviewReplies.findByTenantId(tenantId).collectList();
            Mono<List<ReviewRequest>> requests = reviewRequests
                    .findByTenantIdAndSubjectTypeAndSubjectId(tenantId, subjectType, subjectId)
                    .collectList();
            return Mono.zip(reviews, requests)
                    .map(t -> build(subjectType, subjectId, t.getT1(), t.getT2()));
        });
    }

    private ReviewInsights build(ReviewSubjectType subjectType, UUID subjectId,
                                 List<GbpReviewReply> reviews, List<ReviewRequest> requests) {
        long reviewCount = reviews.size();

        // Average rating over reviews that carried a rating.
        long ratedCount = 0;
        long ratingSum = 0;
        long positive = 0, neutral = 0, negative = 0, unclassified = 0;
        for (GbpReviewReply r : reviews) {
            if (r.getRating() != null) {
                ratedCount++;
                ratingSum += r.getRating();
            }
            ReviewSentiment s = r.getSentiment();
            if (s == null) {
                unclassified++;
            } else {
                switch (s) {
                    case POSITIVE -> positive++;
                    case NEUTRAL -> neutral++;
                    case NEGATIVE -> negative++;
                }
            }
        }
        double averageRating = ratedCount == 0
                ? 0.0
                : BigDecimal.valueOf(ratingSum)
                        .divide(BigDecimal.valueOf(ratedCount), 2, RoundingMode.HALF_UP)
                        .doubleValue();

        // Request funnel — SENT requests only count as "sent" for the response-rate denominator.
        long requestsSent = requests.stream()
                .filter(rq -> rq.getStatus() == ReviewRequest.Status.SENT)
                .count();
        long responded = Math.min(reviewCount, requestsSent); // bounded proxy (see class doc)
        double responseRate = requestsSent == 0
                ? 0.0
                : BigDecimal.valueOf(responded)
                        .divide(BigDecimal.valueOf(requestsSent), 2, RoundingMode.HALF_UP)
                        .doubleValue();

        return new ReviewInsights(
                subjectType, subjectId,
                reviewCount, averageRating,
                positive, neutral, negative, unclassified,
                requestsSent, responded, responseRate);
    }
}
