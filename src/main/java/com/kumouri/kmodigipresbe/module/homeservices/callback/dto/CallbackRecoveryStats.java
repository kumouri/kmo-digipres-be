package com.kumouri.kmodigipresbe.module.homeservices.callback.dto;

/**
 * T5 (Home Services "Instant Callback") — the missed-call → callback <strong>recovery funnel</strong>
 * read projection: how many voicemail leads were offered a callback, how many callers accepted, and how
 * many were dispatched, plus the two conversion rates. The {@code SwitchboardDeflectionStats} precedent.
 *
 * @param offered        callback opt-in SMS sent (the {@code OFFERED} funnel stage)
 * @param accepted       callers who replied opting in (the {@code ACCEPTED} stage)
 * @param dispatched     callbacks a dispatcher claimed (the {@code DISPATCHED} stage)
 * @param acceptanceRate accepted / offered (0.0 when none offered)
 * @param dispatchRate   dispatched / accepted (0.0 when none accepted)
 */
public record CallbackRecoveryStats(
        long offered,
        long accepted,
        long dispatched,
        double acceptanceRate,
        double dispatchRate) {

    /** Build the stats record from the three raw funnel counts, computing the rates safely. */
    public static CallbackRecoveryStats of(long offered, long accepted, long dispatched) {
        double acceptanceRate = offered > 0 ? (double) accepted / offered : 0.0;
        double dispatchRate = accepted > 0 ? (double) dispatched / accepted : 0.0;
        return new CallbackRecoveryStats(offered, accepted, dispatched, acceptanceRate, dispatchRate);
    }
}
