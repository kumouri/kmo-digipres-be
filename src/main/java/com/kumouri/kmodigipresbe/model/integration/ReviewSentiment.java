package com.kumouri.kmodigipresbe.model.integration;

/**
 * The sentiment classification of an ingested Google-Business-Profile review (E3 Review Engine —
 * sentiment triage). Computed by {@code ReviewSentimentService} on ingest and stored as an additive
 * nullable field on {@link GbpReviewReply} (legacy rows deserialize {@code null}).
 *
 * <p>The classification is rating-first (always available, deterministic) and optionally refined by a
 * best-effort Anthropic call for a commented review — see {@link SentimentSource}. A
 * {@code NEGATIVE} sentiment (or a low rating) triggers the best-effort manager alert.
 */
public enum ReviewSentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE;

    /**
     * The classification outcome carried back from {@code ReviewSentimentService.classify} — the
     * sentiment plus how it was determined.
     *
     * @param sentiment the classified sentiment (never null)
     * @param source    how it was determined ({@link SentimentSource#RATING} baseline or
     *                  {@link SentimentSource#AI} refinement)
     */
    public record Result(ReviewSentiment sentiment, SentimentSource source) {
    }
}
