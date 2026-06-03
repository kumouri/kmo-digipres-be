package com.kumouri.kmodigipresbe.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One per-period line of a {@link PayoutReport} (Phase J — J4). Groups a contractor's
 * approved time by its owning {@code Timesheet} period (resolved via
 * {@code TimeEntry.timesheetId}). Entries whose {@code timesheetId} is null fall into a
 * single ungrouped bucket carrying {@code periodStart == null} and {@code periodEnd == null}.
 *
 * <p>All money fields are BigDecimal at scale 2 (HALF_UP). {@code hours} is the sum of
 * {@code durationSeconds / 3600} over the line's entries (scale 2). {@code payout} sums
 * {@code hours × costRateAmount} over entries with a non-null cost rate; {@code bill} sums
 * {@code hours × rateAmount} over entries with a non-null bill rate; {@code margin =
 * bill − payout}. An entry with a null cost rate still contributes its hours to {@code hours}
 * but not to {@code payout} (the unrated-entry surfacing — see {@link PayoutReport}).
 */
public record PayoutPeriodLine(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal hours,
        BigDecimal payout,
        BigDecimal bill,
        BigDecimal margin) {
}
