package com.kumouri.kmodigipresbe.service.contractor;

import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.response.PayoutPeriodLine;
import com.kumouri.kmodigipresbe.model.response.PayoutReport;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.contractor.TimesheetRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.TimeEntryRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 1099 payout + margin rollup (Phase J — J4). For an ADMIN: what is owed a contractor
 * (payout = approved hours × cost rate), what was billed for that work (bill = approved
 * hours × bill rate), and the margin (bill − payout), per {@code Timesheet} period and as a
 * window total / year-to-date.
 *
 * <h2>Approved-only (the J3 gate)</h2>
 * Only entries with {@code approved == true} count — the same invoicing/payout gate
 * {@code TimesheetService} flips in lock-step with the period lifecycle. This service is a
 * pure read; it never mutates the gate.
 *
 * <h2>Unrated entries are surfaced, never silently zeroed</h2>
 * An approved entry whose {@code costRateAmount} is null still contributes its hours to the
 * totals (and to its period line) but contributes nothing to {@code payout}; the report's
 * {@code hasUnratedEntries} flag is set so the admin can fix the missing cost rate before
 * paying out. The bill side is symmetric on {@code rateAmount} (a null bill rate is excluded
 * from {@code bill} but its hours still count).
 *
 * <h2>Money discipline</h2>
 * hours = {@code durationSeconds / 3600} at scale 2 (HALF_UP); money = Σ({@code hours × rate})
 * at scale 2 (HALF_UP); margin = bill − payout. All BigDecimal, no double anywhere.
 */
@Service
@RequiredArgsConstructor
public class PayoutReportService {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final TimeEntryRepository timeEntries;
    private final TimesheetRepository timesheets;
    private final UserRepository users;

    /**
     * Payout + margin over an explicit {@code [from, to]} window. Loads the user's approved
     * entries in the window, resolves each entry's owning {@code Timesheet} period (one
     * fetch per distinct {@code timesheetId}; null {@code timesheetId} → ungrouped bucket),
     * and rolls up the per-period breakdown plus the window totals.
     */
    public Mono<PayoutReport> payout(UUID userId, Instant from, Instant to) {
        return TenantContextHolder.required().flatMap(ctx ->
                timeEntries
                        .findAllByTenantIdAndUserIdAndApprovedTrueAndStartedAtBetween(
                                ctx.tenantId(), userId, from, to)
                        .collectList()
                        .flatMap(entries -> resolvePeriods(ctx, entries)
                                .flatMap(periodsById -> resolveDisplayName(userId)
                                        .map(displayName -> build(
                                                userId, displayName, from, to,
                                                entries, periodsById)))));
    }

    /**
     * Year-to-date payout + margin. The window is {@code [year-01-01T00:00:00Z, now]} when
     * {@code year} is the current UTC year (no point counting time that has not happened),
     * otherwise the full {@code [year-01-01T00:00:00Z, year-12-31T23:59:59Z]} year. UTC
     * throughout — the report is a coarse financial summary, not a zone-precise timesheet.
     */
    public Mono<PayoutReport> payoutYtd(UUID userId, int year) {
        Instant from = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = (year == Year.now(ZoneOffset.UTC).getValue())
                ? Instant.now()
                : LocalDate.of(year, 12, 31).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        return payout(userId, from, to);
    }

    // -------------------------------------------------------------------------
    // Period resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves each distinct non-null {@code timesheetId} present in {@code entries} to its
     * {@code Timesheet} exactly once (tenant-scoped). Returns a map keyed by timesheet id; a
     * timesheet that no longer exists is simply absent (its entries fall back to the
     * ungrouped bucket — defensive, never an error in a read-only report).
     */
    private Mono<Map<UUID, Timesheet>> resolvePeriods(TenantContext ctx, List<TimeEntry> entries) {
        List<UUID> distinctIds = entries.stream()
                .map(TimeEntry::getTimesheetId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Flux.fromIterable(distinctIds)
                .flatMap(id -> timesheets.findByTenantIdAndId(ctx.tenantId(), id))
                .collectMap(Timesheet::getId, ts -> ts);
    }

    /**
     * Resolves the user's display name. {@code users.findById} is tenant-auto-scoped (base
     * repo). A missing user (genuinely absent, or cross-tenant so out of scope) is non-fatal
     * for a read report — it yields the empty-string sentinel, which {@link #build} maps to a
     * null {@code displayName} on the report. The Mono never emits {@code null} (Reactor
     * forbids it); the empty string is the in-band "unknown" marker.
     */
    private Mono<String> resolveDisplayName(UUID userId) {
        return users.findById(userId)
                .map(u -> u.getDisplayName() == null ? "" : u.getDisplayName())
                .defaultIfEmpty("");
    }

    // -------------------------------------------------------------------------
    // Pure aggregation (no I/O below this line)
    // -------------------------------------------------------------------------

    private PayoutReport build(UUID userId, String displayName, Instant from, Instant to,
                               List<TimeEntry> entries, Map<UUID, Timesheet> periodsById) {

        // Accumulate per period bucket, preserving a deterministic order: periods sorted by
        // periodStart, with the null/ungrouped bucket last.
        Map<PeriodKey, Acc> byPeriod = new LinkedHashMap<>();
        Acc total = new Acc();
        boolean hasUnrated = false;

        for (TimeEntry e : entries) {
            BigDecimal hours = hours(e);

            Timesheet ts = e.getTimesheetId() == null ? null : periodsById.get(e.getTimesheetId());
            PeriodKey key = (ts == null)
                    ? PeriodKey.UNGROUPED
                    : new PeriodKey(ts.getPeriodStart(), ts.getPeriodEnd());
            Acc acc = byPeriod.computeIfAbsent(key, k -> new Acc());

            acc.hours = acc.hours.add(hours);
            total.hours = total.hours.add(hours);

            // Payout (cost side): a null cost rate is surfaced, never zeroed into the money.
            if (e.getCostRateAmount() != null) {
                BigDecimal payout = scaleMoney(hours.multiply(e.getCostRateAmount()));
                acc.payout = acc.payout.add(payout);
                total.payout = total.payout.add(payout);
            } else {
                hasUnrated = true;
            }

            // Bill side (symmetric on rateAmount).
            if (e.getRateAmount() != null) {
                BigDecimal bill = scaleMoney(hours.multiply(e.getRateAmount()));
                acc.bill = acc.bill.add(bill);
                total.bill = total.bill.add(bill);
            }
        }

        List<PayoutPeriodLine> lines = new ArrayList<>(byPeriod.size());
        byPeriod.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(PeriodKey.ORDER))
                .forEach(entry -> {
                    PeriodKey k = entry.getKey();
                    Acc a = entry.getValue();
                    lines.add(new PayoutPeriodLine(
                            k.start(), k.end(),
                            scaleMoney(a.hours),
                            scaleMoney(a.payout),
                            scaleMoney(a.bill),
                            scaleMoney(a.bill.subtract(a.payout))));
                });

        return new PayoutReport(
                userId.toString(),
                // empty-string sentinel from resolveDisplayName ⇒ null on the wire
                (displayName == null || displayName.isEmpty()) ? null : displayName,
                from,
                to,
                scaleMoney(total.hours),
                scaleMoney(total.payout),
                scaleMoney(total.bill),
                scaleMoney(total.bill.subtract(total.payout)),
                hasUnrated,
                lines);
    }

    /** Hours = durationSeconds / 3600 at scale 2 (HALF_UP). */
    private static BigDecimal hours(TimeEntry e) {
        return BigDecimal.valueOf(e.getDurationSeconds())
                .divide(SECONDS_PER_HOUR, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleMoney(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** A running BigDecimal accumulator for one bucket (mutable, local to {@link #build}). */
    private static final class Acc {
        private BigDecimal hours = BigDecimal.ZERO;
        private BigDecimal payout = BigDecimal.ZERO;
        private BigDecimal bill = BigDecimal.ZERO;
    }

    /**
     * Period grouping key. The ungrouped bucket ({@code start == null}) sorts last; named
     * periods sort by {@code periodStart} ascending.
     */
    private record PeriodKey(LocalDate start, LocalDate end) {
        static final PeriodKey UNGROUPED = new PeriodKey(null, null);

        static final Comparator<PeriodKey> ORDER =
                Comparator.comparing(PeriodKey::start,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
