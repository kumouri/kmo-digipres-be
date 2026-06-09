package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

import java.util.List;

/**
 * T6 Salon "ReviewBoost" — the salon review dashboard read model: the tenant-wide review-health header
 * plus a per-stylist request-funnel list. The salon generalization of the E3 per-entity insights — where
 * the shipped {@code ReviewInsightsController} answers <em>one</em> {@code (subjectType, subjectId)} (or
 * the whole tenant), this answers <em>every active stylist at once</em>, side-by-side, which is what the
 * salon manager's "which chair drives our reviews?" view needs.
 *
 * <h2>Two blocks (the {@code ReviewInsights} split, made salon-shaped)</h2>
 * <ul>
 *   <li><strong>Review-content header</strong> ({@link #reviewCount}, {@link #averageRating}, sentiment
 *       breakdown) — tenant-level, because Google reviews carry no per-staff attribution. Reported once.</li>
 *   <li><strong>Per-stylist request funnel</strong> ({@link #stylists}) — genuinely entity-attributable,
 *       because each salon review-request is stamped with the visit's stylist. Plus a tenant rollup of the
 *       funnel ({@link #totalRequestsSent} etc.) so the header has the practice-wide ask/response numbers.</li>
 * </ul>
 *
 * @param reviewCount             ingested reviews (tenant-level)
 * @param averageRating           mean star rating over rated reviews (0.0 if none), scale-2
 * @param positiveCount           reviews classified {@code POSITIVE}
 * @param neutralCount            reviews classified {@code NEUTRAL}
 * @param negativeCount           reviews classified {@code NEGATIVE}
 * @param unclassifiedCount       reviews with no stored sentiment yet
 * @param totalRequestsSent       review requests SENT across the whole tenant
 * @param totalRequestsResponded  the bounded response proxy across the tenant (see {@code ReviewInsights})
 * @param overallResponseRate     tenant {@code responded / sent} (0.0 when none), scale-2
 * @param stylists                per-stylist funnel rows, one per active {@code StaffMember}
 */
public record SalonReviewBoardDTO(
        long reviewCount,
        double averageRating,
        long positiveCount,
        long neutralCount,
        long negativeCount,
        long unclassifiedCount,
        long totalRequestsSent,
        long totalRequestsResponded,
        double overallResponseRate,
        List<StylistReviewStatsDTO> stylists) {
}
