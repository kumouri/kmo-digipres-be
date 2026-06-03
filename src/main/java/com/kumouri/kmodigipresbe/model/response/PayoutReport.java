package com.kumouri.kmodigipresbe.model.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The 1099 payout + margin view for a single contractor over a window (Phase J — J4) —
 * what an ADMIN owes the contractor (payout = approved hours × cost rate) and the margin
 * (what was billed − what is owed). The admin reporting analogue of the J2/J3 projection
 * records ({@code TimesheetView}, {@code ContractorProjectView}); built by
 * {@code PayoutReportService} from APPROVED time only (consistent with the J3 invoicing
 * gate).
 *
 * <p><strong>Money discipline.</strong> {@code totalHours}, {@code payout}, {@code bill}
 * and {@code margin} are BigDecimal at scale 2 (HALF_UP). {@code payout} sums
 * {@code hours × costRateAmount} over approved entries with a non-null cost rate;
 * {@code bill} sums {@code hours × rateAmount} over approved entries with a non-null bill
 * rate; {@code margin = bill − payout}.
 *
 * <p><strong>Unrated entries are surfaced, never silently zeroed.</strong>
 * {@code hasUnratedEntries} is {@code true} when any approved entry in the window has a
 * null {@code costRateAmount}. Such entries DO contribute their hours to {@code totalHours}
 * (and to their period line's {@code hours}) but do NOT contribute to {@code payout} — so
 * a contractor's hours never silently disappear from the total, and the admin is signalled
 * to set the missing cost rate before paying out.
 *
 * <p>{@code periods} is the per-{@code Timesheet}-period breakdown; entries with a null
 * {@code timesheetId} land in a single ungrouped line ({@code periodStart}/{@code periodEnd}
 * null).
 */
public record PayoutReport(
        String userId,
        String displayName,
        Instant from,
        Instant to,
        BigDecimal totalHours,
        BigDecimal payout,
        BigDecimal bill,
        BigDecimal margin,
        boolean hasUnratedEntries,
        List<PayoutPeriodLine> periods) {
}
