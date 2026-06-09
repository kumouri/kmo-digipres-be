package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Get Paid" AR-4 — read-only AR-aging dashboard + promise-to-pay management.
 *
 * <h2>Module gate — DEFAULT-OFF</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.ar", name="enabled",
 * matchIfMissing=false)}: this bean does not exist when the AR module is off, so the routes
 * are absent from the OpenAPI spec and return 404 for non-AR deployments — the
 * {@code NoShowRiskController} / {@code AppointmentController} / {@code WaitlistBoardController}
 * precedent (module-gated controllers absent when module flag is off). Additionally, every
 * handler calls {@link TenantModuleRegistry#requireEnabled(String)} for the per-tenant
 * membership check (1130 / 1132 codes, the {@code 4220}/{@code 4202} posture).
 *
 * <h2>AR-aging read — {@code GET /ar/aging}</h2>
 * Returns an {@link ArAgingReport} over this tenant's invoices with
 * {@code status ∈ {SENT, OVERDUE, PARTIALLY_PAID}} and {@code balance > 0}, bucketed by
 * calendar-days past {@code dueAt} using the injected {@link Clock} (same
 * {@code ObjectProvider<Clock>} convention as {@link ArAgingSweepJob} — tests supply a
 * {@code @Primary Clock.fixed(...)}, production falls back to {@code Clock.systemUTC()}).
 * <strong>Read-only, no mutation.</strong>
 *
 * <h3>Multi-currency design decision (AR-4 note)</h3>
 * {@code Invoice.currency} is a free-form String (default {@code "USD"}) — no schema-level
 * single-currency constraint exists. Rather than summing across currencies (silent data
 * corruption), this controller groups buckets by currency. The {@code grandTotalPastDue}
 * and {@code primaryCurrency} fields reflect the most common currency (the one with the
 * highest count). For a single-currency tenant, the FE receives a clean single-currency
 * report with no change required; for a multi-currency tenant, each currency gets its own
 * bucket rows.
 *
 * <h2>Promise-to-pay — {@code POST /ar/promises} + {@code GET /ar/promises?invoiceId=}</h2>
 * Staff records a customer promise: the invoice must exist in the tenant (4601 / 404), the
 * {@code promisedDate} must be today-or-future and {@code promisedAmount} (when given) > 0
 * (4602 / 400). The promise is created ACTIVE. Listing returns all promises for an invoice
 * (any status), newest first.
 *
 * <h2>§9 invariants</h2>
 * <ul>
 *   <li>{@code switchIfEmpty} is used <em>only</em> for genuine entity-not-found errors:
 *       the invoice-not-found 4601 in {@code createPromise}. No {@code switchIfEmpty(create)}
 *       pattern anywhere.</li>
 *   <li>Reactive: the aging aggregation runs on the Netty event loop via a {@code Flux}
 *       pipeline — no blocking I/O.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/ar")
@ConditionalOnProperty(prefix = "kmosf.modules.ar", name = "enabled", matchIfMissing = false)
public class ArAgingController {

    /** Candidate invoice statuses included in the aging report: still open / collectible. */
    private static final List<Invoice.Status> AGING_STATUSES =
            List.of(Invoice.Status.SENT, Invoice.Status.OVERDUE, Invoice.Status.PARTIALLY_PAID);

    private final TenantModuleRegistry modules;
    private final InvoiceRepository invoices;
    private final PromiseToPayRepository promises;
    private final Clock clock;

    public ArAgingController(
            TenantModuleRegistry modules,
            InvoiceRepository invoices,
            PromiseToPayRepository promises,
            ObjectProvider<Clock> clockProvider) {
        this.modules = modules;
        this.invoices = invoices;
        this.promises = promises;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    // ── GET /ar/aging ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the AR-aging dashboard report for the current tenant: open invoices (SENT /
     * OVERDUE / PARTIALLY_PAID, balance > 0) bucketed into CURRENT / D1_7 / D8_14 / D15_30 /
     * D30_PLUS by days past {@code dueAt}. Read-only; module-gated + STAFF.
     */
    @GetMapping("/aging")
    public Mono<ArAgingReport> aging() {
        return guard().then(TenantContextHolder.required()
                .flatMap(ctx -> buildReport(ctx.tenantId())));
    }

    private Mono<ArAgingReport> buildReport(UUID tenantId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        // Aggregate in-memory over the open invoices — the count is bounded by the tenant's
        // open invoice portfolio (never the full dataset). Each bucket key is (label, currency).
        return invoices.findAllByTenantIdAndStatusIn(tenantId, AGING_STATUSES)
                .filter(inv -> inv.getBalance() != null
                        && inv.getBalance().compareTo(BigDecimal.ZERO) > 0)
                .collectList()
                .map(openInvoices -> {
                    // Group: Map<currency, Map<BucketLabel, [count, totalBalance]>>
                    Map<String, Map<ArAgingReport.BucketLabel, long[]>> countMap = new LinkedHashMap<>();
                    Map<String, Map<ArAgingReport.BucketLabel, BigDecimal>> balanceMap = new LinkedHashMap<>();

                    for (Invoice inv : openInvoices) {
                        String currency = inv.getCurrency() != null ? inv.getCurrency() : "USD";
                        ArAgingReport.BucketLabel label = bucketFor(inv, today);

                        countMap.computeIfAbsent(currency, c -> new LinkedHashMap<>())
                                .merge(label, new long[]{1}, (a, b) -> new long[]{a[0] + 1});
                        balanceMap.computeIfAbsent(currency, c -> new LinkedHashMap<>())
                                .merge(label, inv.getBalance(), BigDecimal::add);
                    }

                    // Build the bucket list in canonical order per currency.
                    List<ArAgingReport.AgingBucket> buckets = new ArrayList<>();
                    BigDecimal grandTotal = BigDecimal.ZERO;
                    String primaryCurrency = "USD";
                    long primaryCount = -1;

                    for (String currency : countMap.keySet()) {
                        long currencyCount = countMap.get(currency).values().stream()
                                .mapToLong(a -> a[0]).sum();
                        if (currencyCount > primaryCount) {
                            primaryCount = currencyCount;
                            primaryCurrency = currency;
                        }
                        for (ArAgingReport.BucketLabel label : ArAgingReport.BucketLabel.values()) {
                            long count = countMap.get(currency).getOrDefault(label, new long[]{0})[0];
                            BigDecimal total = balanceMap.get(currency).getOrDefault(label, BigDecimal.ZERO);
                            buckets.add(new ArAgingReport.AgingBucket(label, currency, count, total));
                            // Only past-due buckets contribute to the grand total.
                            if (label != ArAgingReport.BucketLabel.CURRENT) {
                                grandTotal = grandTotal.add(total);
                            }
                        }
                    }

                    // If there are no invoices, return an empty report with USD placeholder.
                    if (buckets.isEmpty()) {
                        for (ArAgingReport.BucketLabel label : ArAgingReport.BucketLabel.values()) {
                            buckets.add(new ArAgingReport.AgingBucket(label, "USD", 0, BigDecimal.ZERO));
                        }
                    }

                    return new ArAgingReport(buckets, primaryCurrency, grandTotal);
                });
    }

    private ArAgingReport.BucketLabel bucketFor(Invoice inv, LocalDate today) {
        LocalDate dueAt = inv.getDueAt();
        if (dueAt == null) {
            // Invoices without dueAt are not yet overdue — treat as CURRENT.
            return ArAgingReport.BucketLabel.CURRENT;
        }
        long daysOverdue = ChronoUnit.DAYS.between(dueAt, today);
        if (daysOverdue <= 0) return ArAgingReport.BucketLabel.CURRENT;
        if (daysOverdue <= 7) return ArAgingReport.BucketLabel.D1_7;
        if (daysOverdue <= 14) return ArAgingReport.BucketLabel.D8_14;
        if (daysOverdue <= 30) return ArAgingReport.BucketLabel.D15_30;
        return ArAgingReport.BucketLabel.D30_PLUS;
    }

    // ── POST /ar/promises ─────────────────────────────────────────────────────────────────────

    /**
     * Staff records a customer promise-to-pay for an invoice. Validates that the invoice exists
     * in the tenant (4601 / 404) and that the date is today-or-future + amount (when given) > 0
     * (4602 / 400). Creates an ACTIVE promise.
     *
     * <p>{@code switchIfEmpty} in this method is the <em>genuine not-found</em> case (4601) — the
     * only allowed use per §9. No {@code switchIfEmpty(create)} pattern.
     */
    @PostMapping("/promises")
    public Mono<PromiseToPay> createPromise(@RequestBody PromiseToPayRequest req) {
        return guard().then(TenantContextHolder.required()
                .flatMap(ctx -> doCreatePromise(ctx.tenantId(), req)));
    }

    private Mono<PromiseToPay> doCreatePromise(UUID tenantId, PromiseToPayRequest req) {
        if (req.invoiceId() == null) {
            return Mono.error(new DigiPresBeException(
                    "invoiceId is required", 4602, 400));
        }
        if (req.promisedDate() == null) {
            return Mono.error(new DigiPresBeException(
                    "promisedDate is required", 4602, 400));
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        // Validate promisedDate must be today-or-future.
        if (req.promisedDate().isBefore(today)) {
            return Mono.error(new DigiPresBeException(
                    "promisedDate must be today or in the future", 4602, 400));
        }
        // Validate promisedAmount > 0 if given.
        if (req.promisedAmount() != null
                && req.promisedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new DigiPresBeException(
                    "promisedAmount must be greater than 0", 4602, 400));
        }

        // Validate invoice exists in the tenant — genuine switchIfEmpty(4601 not-found).
        return invoices.findByTenantIdAndId(tenantId, req.invoiceId())
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "Invoice not found: " + req.invoiceId(), 4601, 404)))
                .flatMap(invoice -> {
                    PromiseToPay promise = PromiseToPay.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .invoiceId(req.invoiceId())
                            .contactId(invoice.getContactId()) // inherit from invoice
                            .promisedAmount(req.promisedAmount())
                            .promisedDate(req.promisedDate())
                            .status(PromiseToPay.Status.ACTIVE)
                            .note(req.note())
                            .build();
                    return promises.save(promise);
                });
    }

    // ── GET /ar/promises?invoiceId= ───────────────────────────────────────────────────────────

    /**
     * Lists all promises for a given invoice (any status), newest first. Module-gated + STAFF.
     */
    @GetMapping("/promises")
    public Flux<PromiseToPay> listPromises(@RequestParam UUID invoiceId) {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx ->
                        promises.findAllByTenantIdAndInvoiceIdOrderByCreatedAtDesc(
                                ctx.tenantId(), invoiceId)));
    }

    // ── common guard ──────────────────────────────────────────────────────────────────────────

    private Mono<Void> guard() {
        return modules.requireEnabled(ArAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
