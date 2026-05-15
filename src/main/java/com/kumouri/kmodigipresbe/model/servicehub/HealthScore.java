package com.kumouri.kmodigipresbe.model.servicehub;

import java.time.Instant;
import java.util.List;

/**
 * Embedded health score — not a top-level document. Stored directly on
 * {@link com.kumouri.kmodigipresbe.model.contact.Contact} and
 * {@link com.kumouri.kmodigipresbe.model.contact.Company} as an optional
 * {@code healthScore} field. Null until the nightly compute runs.
 */
public record HealthScore(
        int score,
        HealthScoreTier tier,
        List<String> drivers,
        Instant calculatedAt
) {
    public static HealthScore of(int raw, List<String> drivers) {
        int clamped = Math.max(0, Math.min(100, raw));
        HealthScoreTier tier = clamped >= 70 ? HealthScoreTier.GREEN
                : clamped >= 40 ? HealthScoreTier.YELLOW
                : HealthScoreTier.RED;
        return new HealthScore(clamped, tier, List.copyOf(drivers), Instant.now());
    }
}
