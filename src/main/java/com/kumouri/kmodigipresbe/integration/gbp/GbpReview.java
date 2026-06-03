package com.kumouri.kmodigipresbe.integration.gbp;

import java.time.Instant;

/**
 * A single Google-Business-Profile review, as normalized by {@link GbpApiClient#fetchReviews}
 * (the adapter boundary — the raw GBP JSON shape is assumed in the client only).
 *
 * @param reviewId        the GBP review resource id (e.g. the {@code reviews/*} name) — the
 *                        per-tenant idempotency key
 * @param rating          star rating 1..5 ({@code null} if the payload omitted it)
 * @param comment         the reviewer's free-text comment ({@code null} for a star-only review)
 * @param reviewerName    the reviewer's display name ({@code null} if absent)
 * @param createTime      when the review was created on Google ({@code null} if unparseable/absent)
 */
public record GbpReview(
        String reviewId,
        Integer rating,
        String comment,
        String reviewerName,
        Instant createTime) {
}
