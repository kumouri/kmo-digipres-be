package com.kumouri.kmodigipresbe.model.nurture;

/**
 * Vertical-agnostic dormancy tiers for a nurture campaign (E1 — Nurture / Cadence Engine).
 *
 * <p>{@code A} = the most-recently-active dormant cohort … {@code D} = the longest-dormant / coldest.
 * The day-window (and optional value-band) that maps a contact into a bucket is carried by the
 * campaign's {@link NurtureSegmentDefinition} list — <strong>never hardcoded in code</strong>. So the
 * same engine reactivates Real Estate past-buyer leads, Health lapsed patients, and Home unaccepted
 * quotes purely by seeding different campaign documents (the vertical-agnostic payoff).
 */
public enum DormancyBucket {
    A,
    B,
    C,
    D
}
