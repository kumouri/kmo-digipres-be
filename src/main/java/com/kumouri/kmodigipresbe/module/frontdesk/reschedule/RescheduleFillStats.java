package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * T7 — the RescheduleFlow fill-funnel read model (the {@code GET /frontdesk/reschedule/fill-stats}
 * response). PHI-free counters only (the {@code SwitchboardDeflectionStats} posture).
 *
 * @param cancellations cancelled appointments that kicked off a gap-fill
 * @param offers        gap-fills that issued at least one ranked offer
 * @param claims        inbound YESs that won a freed slot (atomic claim resolved a winner)
 * @param filled        winning claims that materialized a real PHI-free replacement appointment
 * @param fillRate      fraction of cancellations that ended in a filled slot ({@code filled / cancellations}),
 *                      0.0 when {@code cancellations == 0}, scale-4 HALF_UP
 */
public record RescheduleFillStats(
        long cancellations,
        long offers,
        long claims,
        long filled,
        double fillRate) {

    /** Builds the stats from the four stage counts (computes fillRate from filled / cancellations). */
    public static RescheduleFillStats of(long cancellations, long offers, long claims, long filled) {
        double rate = cancellations == 0
                ? 0.0
                : BigDecimal.valueOf(filled)
                        .divide(BigDecimal.valueOf(cancellations), 4, RoundingMode.HALF_UP)
                        .doubleValue();
        return new RescheduleFillStats(cancellations, offers, claims, filled, rate);
    }
}
