package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

import java.util.UUID;

/**
 * T6 Salon "ReviewBoost" — per-stylist review-request funnel stats for one staff member, returned as a
 * row in the {@link SalonReviewBoardDTO} list.
 *
 * <p>This is the genuinely entity-attributable part of the E3 review insights: a salon post-visit
 * review-request is stamped with the stylist who did the visit ({@code ReviewSubjectType.STAFF} + the
 * booking's {@code staffMemberId} — set by the shipped {@code ReviewRequestService} on
 * {@code BOOKING_COMPLETED}), so the request → response funnel below can be scoped per stylist. The
 * review-content (count / rating / sentiment) lives at the board header instead, because Google reviews
 * carry no per-staff attribution (the {@code ReviewInsights} honesty note).
 *
 * @param staffMemberId      the stylist's {@code StaffMember} id (the review-request {@code subjectId})
 * @param displayName        the stylist's display name (from {@code StaffMember})
 * @param requestsSent       review requests SENT attributed to this stylist
 * @param requestsResponded  the bounded response proxy for this stylist (see {@code ReviewInsights} — GBP
 *                           reviews are not request-correlated, so this is {@code min(tenantReviewCount,
 *                           requestsSent)}, never &gt; sent)
 * @param responseRate       {@code requestsResponded / requestsSent} (0.0 when none sent), scale-2
 */
public record StylistReviewStatsDTO(
        UUID staffMemberId,
        String displayName,
        long requestsSent,
        long requestsResponded,
        double responseRate) {
}
