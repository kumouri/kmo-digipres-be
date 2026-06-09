package com.kumouri.kmodigipresbe.model.integration;

/**
 * How a {@link ReviewSentiment} was determined (E3 Review Engine — sentiment triage). Stored as an
 * additive nullable field on {@link GbpReviewReply} alongside the sentiment.
 *
 * <ul>
 *   <li>{@code RATING} — derived deterministically from the star rating (the always-available
 *       baseline, and the fallback when the AI refinement is unavailable / a review has no comment).</li>
 *   <li>{@code AI} — refined by a best-effort Anthropic classification of the review comment (used only
 *       when a commented review's AI call succeeds within budget; degrades to {@code RATING} otherwise —
 *       AI is triage, not truth).</li>
 * </ul>
 */
public enum SentimentSource {
    RATING,
    AI
}
