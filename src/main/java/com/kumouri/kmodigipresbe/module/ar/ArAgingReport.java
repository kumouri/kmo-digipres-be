package com.kumouri.kmodigipresbe.module.ar;

import java.math.BigDecimal;
import java.util.List;

/**
 * "Get Paid" AR-4 — the read-only AR-aging dashboard DTO returned by
 * {@code GET /ar/aging}.
 *
 * <p>Invoices with {@code balance > 0} and status in
 * {@code {SENT, OVERDUE, PARTIALLY_PAID}} are bucketed by calendar-days past {@code dueAt}
 * (using the injected {@code Clock} so tests can supply a fixed instant). The CURRENT bucket
 * holds invoices whose {@code dueAt} is today or in the future (not yet past due).
 *
 * <p>Currency: this codebase stores {@code Invoice.currency} as a free-form String (default
 * {@code "USD"}) — there is no schema-level single-currency constraint. Rather than
 * silently summing across currencies, the report groups buckets by currency. When a tenant
 * has only USD invoices (the overwhelmingly common case) there is exactly one {@code currency}
 * entry in each bucket. This is the safest product choice: no invisible currency-mixing
 * error; FE can display a single row for single-currency tenants without change. See
 * {@code ArAgingController} for the full design note.
 */
public record ArAgingReport(
        List<AgingBucket> buckets,
        String primaryCurrency,
        BigDecimal grandTotalPastDue
) {

    /**
     * One aging bucket: a time-range label + the invoices that fall into it for a given
     * currency.
     */
    public record AgingBucket(
            BucketLabel label,
            String currency,
            long count,
            BigDecimal totalBalance
    ) {}

    /**
     * The five aging buckets.
     * <ul>
     *   <li>{@code CURRENT} — {@code dueAt} >= today (not yet past due)</li>
     *   <li>{@code D1_7}    — 1–7 days past due</li>
     *   <li>{@code D8_14}   — 8–14 days past due</li>
     *   <li>{@code D15_30}  — 15–30 days past due</li>
     *   <li>{@code D30_PLUS} — more than 30 days past due</li>
     * </ul>
     */
    public enum BucketLabel { CURRENT, D1_7, D8_14, D15_30, D30_PLUS }
}
