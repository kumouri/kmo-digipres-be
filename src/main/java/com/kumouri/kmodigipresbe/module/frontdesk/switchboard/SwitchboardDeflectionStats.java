package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * T4 — the Switchboard deflection-analytics read model (the {@code GET /frontdesk/switchboard/
 * deflection-stats} response). PHI-free counters only.
 *
 * @param logistics      messages answered by the logistics handler (front-desk overflow handled)
 * @param tripwire       clinical/symptom messages handed off by the tripwire (no transcript kept)
 * @param handoff        unmatched/UNKNOWN messages handed off to staff by the default handoff
 * @param total          {@code logistics + tripwire + handoff}
 * @param deflectionRate fraction of all messages the AI resolved without a staff handoff
 *                       ({@code logistics / total}), 0.0 when {@code total == 0}, scale-4 HALF_UP
 */
public record SwitchboardDeflectionStats(
        long logistics,
        long tripwire,
        long handoff,
        long total,
        double deflectionRate) {

    /** Builds the stats from the three category counts (computes total + deflectionRate). */
    public static SwitchboardDeflectionStats of(long logistics, long tripwire, long handoff) {
        long total = logistics + tripwire + handoff;
        double rate = total == 0
                ? 0.0
                : BigDecimal.valueOf(logistics)
                        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                        .doubleValue();
        return new SwitchboardDeflectionStats(logistics, tripwire, handoff, total, rate);
    }
}
