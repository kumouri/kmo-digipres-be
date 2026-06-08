package com.kumouri.kmodigipresbe.module.realestate.model;

/**
 * Real Estate Concierge (RE-4 — Marketing Studio) — the marketing surfaces the studio drafts copy for.
 *
 * <p>The {@code MLS_REMARKS} are the listing's MLS public remarks; {@code EMAIL_BLAST} is the email body;
 * the rest are platform-tuned social captions ({@code INSTAGRAM} / {@code FACEBOOK} / {@code X}, each
 * length/tone-tuned by the generation prompt). The draft persists one {@link ListingMarketingDraft.GeneratedPiece}
 * per channel; the agent reviews + approves them all together (the GBP draft→approve posture — never
 * auto-published).
 */
public enum MarketingChannel {
    MLS_REMARKS,
    INSTAGRAM,
    FACEBOOK,
    X,
    EMAIL_BLAST
}
