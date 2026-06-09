package com.kumouri.kmodigipresbe.module.realestate.responder;

/**
 * T3 (Real Estate "Midnight Responder") — the aggregated responder-latency read (the "&lt;30s, 24/7" demo
 * stat). Computed in-service over the tenant's {@code ConciergeConversation} assistant turns; no new
 * collection.
 *
 * @param repliedTurns       the number of assistant turns that carried a recorded received→replied latency
 * @param p50LatencyMs       the median received→replied latency in ms (0 when no timed turns)
 * @param p95LatencyMs       the 95th-percentile received→replied latency in ms (0 when no timed turns)
 * @param maxLatencyMs       the slowest received→replied latency in ms (0 when no timed turns)
 * @param afterHoursTurns    the number of buyer turns received OUTSIDE the configured business-hours window
 * @param totalBuyerTurns    the number of buyer turns with a recorded receipt time (the after-hours base)
 * @param afterHoursShare    {@code afterHoursTurns / totalBuyerTurns} in {@code [0.0, 1.0]} (0 when none)
 * @param afterHoursStartHour the business-hours window start (local hour, inclusive) used for the share
 * @param afterHoursEndHour   the business-hours window end (local hour, exclusive) used for the share
 */
public record MidnightResponderLatencyStats(
        long repliedTurns,
        long p50LatencyMs,
        long p95LatencyMs,
        long maxLatencyMs,
        long afterHoursTurns,
        long totalBuyerTurns,
        double afterHoursShare,
        int afterHoursStartHour,
        int afterHoursEndHour) {
}
