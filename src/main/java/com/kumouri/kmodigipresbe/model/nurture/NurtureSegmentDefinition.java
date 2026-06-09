package com.kumouri.kmodigipresbe.model.nurture;

import java.math.BigDecimal;

/**
 * A single pluggable dormancy-segment rule embedded on a {@link NurtureCampaign} (E1 — Nurture /
 * Cadence Engine). This is what makes the engine <strong>vertical-agnostic</strong>: the thresholds
 * that map a contact into a {@link DormancyBucket} live in the campaign document, never in code.
 *
 * <p>A contact matches this rule when its days-since-last-activity falls in
 * {@code [minDaysSinceLastActivity, maxDaysSinceLastActivity)} (the upper bound exclusive; {@code null}
 * = open-ended) <strong>and</strong>, if a value-band is set, its lifetime value (Σ WON-deal value)
 * falls in {@code [minLifetimeValue, maxLifetimeValue]} (each bound nullable / optional). A contact is
 * assigned the <em>first</em> matching segment in the campaign's ordered list.
 *
 * @param bucket                   the dormancy tier this rule defines
 * @param minDaysSinceLastActivity inclusive lower bound on days dormant (>= 0)
 * @param maxDaysSinceLastActivity exclusive upper bound on days dormant; {@code null} = open-ended
 * @param minLifetimeValue         optional inclusive lifetime-value floor; {@code null} = no floor
 * @param maxLifetimeValue         optional inclusive lifetime-value ceiling; {@code null} = no ceiling
 */
public record NurtureSegmentDefinition(
        DormancyBucket bucket,
        int minDaysSinceLastActivity,
        Integer maxDaysSinceLastActivity,
        BigDecimal minLifetimeValue,
        BigDecimal maxLifetimeValue) {

    /** True iff this rule defines a lifetime-value band (so segmentation must compute it). */
    public boolean hasValueBand() {
        return minLifetimeValue != null || maxLifetimeValue != null;
    }

    /** True iff {@code daysDormant} falls in this rule's day window. */
    public boolean matchesDays(long daysDormant) {
        if (daysDormant < minDaysSinceLastActivity) {
            return false;
        }
        return maxDaysSinceLastActivity == null || daysDormant < maxDaysSinceLastActivity;
    }

    /** True iff {@code lifetimeValue} falls in this rule's (optional) value band. */
    public boolean matchesValue(BigDecimal lifetimeValue) {
        BigDecimal v = lifetimeValue == null ? BigDecimal.ZERO : lifetimeValue;
        if (minLifetimeValue != null && v.compareTo(minLifetimeValue) < 0) {
            return false;
        }
        return maxLifetimeValue == null || v.compareTo(maxLifetimeValue) <= 0;
    }
}
