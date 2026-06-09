package com.kumouri.kmodigipresbe.module.homeservices.callback;

import java.time.Instant;

/**
 * T5 (Home Services "Instant Callback") — the <strong>pure, static, deterministic</strong> revenue ranker
 * for the dispatcher callback queue (no ML — the {@code WaitlistRankingService} "deterministic ranking"
 * posture). Produces the {@link CallbackRequest#getRevenueScore()} stamped at create, so
 * {@code GET /home-services/callbacks} is a pure DB sort on the {@code tenant_status_score_idx} index;
 * the score is fully re-derivable from the same inputs.
 *
 * <h2>Ranking signal (highest works first)</h2>
 * <ol>
 *   <li><strong>Job-value band</strong> (primary) — the coarse $-band the home-services voicemail intake
 *       stamped on the DRAFT WorkOrder ({@code customFields.jobValueBand}): LARGE &gt; MEDIUM &gt; SMALL
 *       &gt; unknown. A bigger ticket is called back first.</li>
 *   <li><strong>Urgency</strong> (secondary) — the routing urgency the same intake stamped
 *       ({@code customFields.urgency}): EMERGENCY &gt; URGENT &gt; ROUTINE &gt; untriaged.</li>
 *   <li><strong>Recency</strong> (tie-break) — a newer request edges out an older one of the same band +
 *       urgency, so a fresh lead is not buried.</li>
 * </ol>
 * The score packs band × {@link #BAND_WEIGHT} + urgency × {@link #URGENCY_WEIGHT} + a bounded recency
 * term, so the lexicographic priority (band, then urgency, then recency) holds in a single {@code long}.
 */
public final class CallbackRevenueRanker {

    /** Band dominates urgency: a one-band step outranks any urgency difference. */
    static final long BAND_WEIGHT = 1_000_000L;
    /** Urgency dominates recency: a one-urgency step outranks any recency difference. */
    static final long URGENCY_WEIGHT = 10_000L;

    private CallbackRevenueRanker() {
    }

    /**
     * Compute the deterministic revenue score from the (nullable) job-value band + urgency labels the
     * voicemail intake produced, plus a reference instant (the parsed requested window if present, else
     * the create time) for the recency tie-break. Null/garbage labels score as the lowest tier (never
     * throws).
     */
    public static long score(String jobValueBand, String urgency, Instant reference) {
        long band = bandRank(jobValueBand);
        long urg = urgencyRank(urgency);
        long recency = recencyTerm(reference);
        return band * BAND_WEIGHT + urg * URGENCY_WEIGHT + recency;
    }

    /** LARGE=3, MEDIUM=2, SMALL=1, unknown/garbage=0. */
    static long bandRank(String band) {
        if (band == null) {
            return 0L;
        }
        return switch (band.trim().toUpperCase()) {
            case "LARGE" -> 3L;
            case "MEDIUM" -> 2L;
            case "SMALL" -> 1L;
            default -> 0L;
        };
    }

    /** EMERGENCY=3, URGENT=2, ROUTINE=1, untriaged/unknown/garbage=0. */
    static long urgencyRank(String urgency) {
        if (urgency == null) {
            return 0L;
        }
        return switch (urgency.trim().toUpperCase()) {
            case "EMERGENCY" -> 3L;
            case "URGENT" -> 2L;
            case "ROUTINE" -> 1L;
            default -> 0L;
        };
    }

    /**
     * A bounded recency term (epoch seconds modulo {@link #URGENCY_WEIGHT}) so a newer request edges an
     * older one of identical band+urgency without ever overflowing into the urgency tier. A null reference
     * scores 0 (oldest).
     */
    private static long recencyTerm(Instant reference) {
        if (reference == null) {
            return 0L;
        }
        long secs = reference.getEpochSecond();
        long mod = Math.floorMod(secs, URGENCY_WEIGHT);
        return Math.max(0L, Math.min(mod, URGENCY_WEIGHT - 1));
    }
}
