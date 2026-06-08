package com.kumouri.kmodigipresbe.module.chairfill.model;

import java.time.Instant;

/**
 * Derived no-show risk score embedded on an upcoming salon
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}. Refreshed nightly by
 * {@link com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService}.
 *
 * <p><strong>Mirror of {@link com.kumouri.kmodigipresbe.model.scoring.LeadScore}, NOT a reuse.</strong>
 * The shipped lead-scorer scores Contacts off Deal outcomes for the whole CRM; this scores
 * salon Bookings off Booking outcomes (COMPLETED vs NO_SHOW) and is chairfill-module-gated.
 * Keeping a separate type leaves the NMM-relied-upon lead-scorer regression-proof (CF-1 D1).
 *
 * <p>{@code riskScore} is P(no-show) in {@code [0,1]}. <em>Note the label polarity is the
 * mirror-image of the lead-scorer's WON=1</em>: here NO_SHOW=1 / COMPLETED=0, so a HIGH
 * {@code riskScore} means likely to no-show.
 *
 * <p>{@code source} indicates which algorithm produced the score:
 * <ul>
 *   <li>{@code "MODEL"} — Smile {@code LogisticRegression} trained on &ge;
 *       {@code MIN_BOOKINGS_FOR_MODEL} terminal (COMPLETED/NO_SHOW) bookings</li>
 *   <li>{@code "RULES_FALLBACK"} — the deterministic cold-start rules used when the
 *       salon has too few terminal bookings to train a model (the night-one path)</li>
 *   <li>{@code "INSUFFICIENT_DATA"} — a first-time booking with no usable history;
 *       scored LOW so a brand-new client is never punished with a deposit demand on
 *       zero evidence</li>
 * </ul>
 *
 * <p>{@code riskTier} is the {@link NoShowRiskTier} name. Thresholds (tunable via config):
 * {@code >= 0.6} HIGH, {@code >= 0.35} MEDIUM, else LOW.
 */
public record NoShowRisk(
        double riskScore,
        String riskTier,
        String source,
        Instant computedAt) {

    public static final String SOURCE_MODEL = "MODEL";
    public static final String SOURCE_RULES_FALLBACK = "RULES_FALLBACK";
    public static final String SOURCE_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    public static final String TIER_LOW = NoShowRiskTier.LOW.name();
    public static final String TIER_MEDIUM = NoShowRiskTier.MEDIUM.name();
    public static final String TIER_HIGH = NoShowRiskTier.HIGH.name();

    /** Default tier thresholds (CF-1 D1); the service may override via config. */
    public static final double DEFAULT_HIGH_THRESHOLD = 0.6;
    public static final double DEFAULT_MEDIUM_THRESHOLD = 0.35;

    public static String tierFrom(double riskScore) {
        return tierFrom(riskScore, DEFAULT_HIGH_THRESHOLD, DEFAULT_MEDIUM_THRESHOLD);
    }

    public static String tierFrom(double riskScore, double highThreshold, double mediumThreshold) {
        if (riskScore >= highThreshold) return TIER_HIGH;
        if (riskScore >= mediumThreshold) return TIER_MEDIUM;
        return TIER_LOW;
    }
}
