package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;

import java.util.UUID;

/**
 * A generic review-insights rollup (E3 Review Engine — per-entity insights). Returned by the ADMIN
 * read endpoints for either the whole tenant or a single {@code (subjectType, subjectId)} entity.
 *
 * <h2>Two blocks, because the two data sources attribute differently</h2>
 * <ul>
 *   <li><strong>Review-content metrics</strong> ({@link #reviewCount}, {@link #averageRating},
 *       {@link #positiveCount}/{@link #neutralCount}/{@link #negativeCount}) come from
 *       {@code GbpReviewReply}. Google reviews carry <em>no</em> per-staff / per-job attribution, so
 *       these are inherently <strong>tenant-level</strong>: the per-entity endpoint reports the same
 *       tenant-wide review-content block (so a stylist's page still shows the practice's review health)
 *       while the request funnel below is the genuinely entity-attributable part.</li>
 *   <li><strong>Request funnel</strong> ({@link #requestsSent}, {@link #requestsResponded},
 *       {@link #responseRate}) comes from {@code ReviewRequest}, which E3 stamps with
 *       {@code (subjectType, subjectId)} — so this block is scoped to the requested entity on the
 *       per-entity endpoint, and to the whole tenant on the tenant endpoint.</li>
 * </ul>
 *
 * @param subjectType         the attribution dimension this rollup is scoped to ({@code null} for the
 *                            whole-tenant rollup)
 * @param subjectId           the entity id this rollup is scoped to ({@code null} for the whole-tenant rollup)
 * @param reviewCount         number of ingested reviews (tenant-level — see class doc)
 * @param averageRating       mean star rating over reviews that carried a rating (0.0 if none), scale-2
 * @param positiveCount       reviews classified {@code POSITIVE}
 * @param neutralCount        reviews classified {@code NEUTRAL}
 * @param negativeCount       reviews classified {@code NEGATIVE}
 * @param unclassifiedCount   reviews with no stored sentiment yet (ingested before E3 / sentiment null)
 * @param requestsSent        review requests SENT (scoped to the subject on the per-entity endpoint)
 * @param requestsResponded   the response proxy = min(reviewCount, requestsSent) (see class doc — GBP
 *                            reviews are not request-correlated, so this is a bounded proxy, never > sent)
 * @param responseRate        {@code requestsResponded / requestsSent} (0.0 when none sent), scale-2
 */
public record ReviewInsights(
        ReviewSubjectType subjectType,
        UUID subjectId,
        long reviewCount,
        double averageRating,
        long positiveCount,
        long neutralCount,
        long negativeCount,
        long unclassifiedCount,
        long requestsSent,
        long requestsResponded,
        double responseRate) {
}
