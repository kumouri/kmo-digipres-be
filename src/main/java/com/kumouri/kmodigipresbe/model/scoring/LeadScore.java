package com.kumouri.kmodigipresbe.model.scoring;

import java.time.Instant;

/**
 * Derived lead quality score embedded on {@link com.kumouri.kmodigipresbe.model.contact.Contact}.
 * Refreshed nightly by {@link com.kumouri.kmodigipresbe.service.ai.scoring.LeadScoringV2Service}.
 *
 * <p>{@code source} indicates which algorithm produced the score:
 * <ul>
 *   <li>{@code "MODEL"} — Smile RandomForest trained on ≥ 50 closed deals</li>
 *   <li>{@code "RULES_FALLBACK"} — engagement-based rules used when fewer than 50
 *       closed deals exist (tenant is too new to train a model)</li>
 *   <li>{@code "INSUFFICIENT_DATA"} — contact has too little activity to score even
 *       with the rules-based approach</li>
 * </ul>
 */
public record LeadScore(
        double score,
        String tier,
        String source,
        Instant computedAt) {

    public static final String SOURCE_MODEL = "MODEL";
    public static final String SOURCE_RULES_FALLBACK = "RULES_FALLBACK";
    public static final String SOURCE_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    public static final String TIER_HOT = "HOT";
    public static final String TIER_WARM = "WARM";
    public static final String TIER_COLD = "COLD";

    public static String tierFrom(double score) {
        if (score >= 0.7) return TIER_HOT;
        if (score >= 0.4) return TIER_WARM;
        return TIER_COLD;
    }
}
