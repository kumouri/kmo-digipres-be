package com.kumouri.kmodigipresbe.service.recurring;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice.Status;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoiceOccurrence;
import com.kumouri.kmodigipresbe.repository.recurring.RecurringInvoiceOccurrenceRepository;
import com.kumouri.kmodigipresbe.repository.recurring.RecurringInvoiceRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.service.scheduling.RecurringSchedule;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The recurring-billing money core (Phase E — E-D3). Materializes exactly ONE
 * DRAFT {@link Invoice} per cadence period, safe under Quartz re-fire / misfire /
 * restart, via the existing {@code InvoiceService.create} DRAFT seam (invoicing is
 * NOT reinvented).
 *
 * <h2>Idempotency (E-D3 / §9 item 3 — the grep target)</h2>
 * Two layers, both required:
 * <ol>
 *   <li><strong>Explicit-boolean occurrence probe</strong>:
 *       {@code findByTenantIdAndRecurringInvoiceIdAndPeriodKey(...).map(e -> true)
 *       .defaultIfEmpty(false).flatMap(seen -> seen ? Mono.empty() : doSpawn(...))}.
 *       NEVER {@code switchIfEmpty(doSpawn)} — that fires whenever the probe
 *       completes empty and would double-create (the {@code IdempotencyWebFilter}
 *       documented trap). {@code switchIfEmpty} appears in this file ONLY around a
 *       genuine not-found (3605 in {@code RecurringInvoiceService}, not here).</li>
 *   <li><strong>Ledger-insert FIRST</strong>: {@code doSpawn} saves the
 *       {@link RecurringInvoiceOccurrence} row (unique
 *       {@code tenant_recurring_period_idx}) BEFORE {@code invoiceService.create}.
 *       A duplicate-fire loser hits {@code DuplicateKeyException} → {@code Mono.empty()}
 *       → ZERO invoice, ZERO double-bill, ZERO orphan DRAFT. The unique index gates
 *       before any money object exists.</li>
 * </ol>
 *
 * <h2>Blocking-Quartz-on-reactive bridge (§9 item 2)</h2>
 * {@link #runDueOnce()} is called with {@code .block()} from
 * {@code RecurringInvoiceSpawnJob.execute()} which runs on a Quartz worker (a
 * bounded blocking pool — NOT the Netty event loop). The synchronous-throwing
 * {@code recurringSchedule.expand} is wrapped in {@code Mono.fromCallable} (the
 * {@code ServiceAgreementSchedulerService:100-101} precedent) so a bad RRULE flows
 * through the pipeline and is swallowed per-row. No reactive chain runs on the
 * event loop; no {@code Mono} is left unsubscribed.
 *
 * <h2>Restart durability (E-D5 RAM Quartz store)</h2>
 * The RAM Quartz store is non-durable by design; durability of "which periods were
 * spawned" lives in the {@link RecurringInvoiceOccurrence} ledger +
 * {@link RecurringInvoice#getNextRunAt()} cursor. A stateless tick re-running
 * {@link #runDueOnce()} reconciles from the ledger every hour (bounded catch-up).
 */
@Slf4j
@Service
public class RecurringInvoiceSpawnService {

    public static final String SYSTEM_ROLE = "RECURRING_INVOICE_SCHEDULER";

    private final RecurringInvoiceRepository recurringInvoices;
    private final RecurringInvoiceOccurrenceRepository occurrences;
    private final RecurringSchedule recurringSchedule;
    private final InvoiceService invoiceService;
    private final DomainEventPublisher events;
    private final Clock clock;

    /**
     * Explicit constructor (not Lombok {@code @RequiredArgsConstructor}) so the
     * {@link Clock} resolves via {@link ObjectProvider} with a {@code systemUTC}
     * fallback — the codebase has no global {@code Clock} bean and the
     * {@code HomeServicesAutoConfiguration} precedent uses exactly this pattern.
     * A test that registers a fixed {@code Clock} bean overrides it.
     */
    public RecurringInvoiceSpawnService(RecurringInvoiceRepository recurringInvoices,
                                        RecurringInvoiceOccurrenceRepository occurrences,
                                        RecurringSchedule recurringSchedule,
                                        InvoiceService invoiceService,
                                        DomainEventPublisher events,
                                        ObjectProvider<Clock> clockProvider) {
        this.recurringInvoices = recurringInvoices;
        this.occurrences = occurrences;
        this.recurringSchedule = recurringSchedule;
        this.invoiceService = invoiceService;
        this.events = events;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Bound on catch-up invoices materialized per template per tick — one invoice
     * per missed period, never a merged lump (AC-E3), capped so a long-dormant
     * template can't flood billing in a single tick.
     */
    @Value("${kmosf.recurring-invoice.max-catchup:12}")
    private int maxCatchup;

    /**
     * Visible for tests / the Quartz job. Runs one spawn pass across all due
     * recurring invoices (all tenants). Per-row errors are swallowed so one bad
     * template can't poison the tick.
     */
    public Mono<Void> runDueOnce() {
        return recurringInvoices.findAllDueAcrossTenants(clock.instant())
                .flatMap(this::spawnDueForSafe)
                .then();
    }

    /**
     * Manual single-template spawn ({@code POST /recurring-invoices/{id}/spawn-now}
     * — E-D12). Loads the template tenant-scoped (404/3605 if absent) and runs the
     * <strong>identical</strong> idempotent ledger-first {@code spawnDueFor} path,
     * so a double-click / re-fire produces exactly one invoice + one ledger row
     * (the {@code @IdempotentRoute} on the endpoint is the belt; the occurrence
     * ledger is the guarantee). Errors are NOT swallowed here (unlike the tick) —
     * a manual trigger should surface a bad RRULE (1300) etc. to the caller.
     */
    public Mono<Void> spawnNow(UUID recurringInvoiceId) {
        return TenantContextHolder.required().flatMap(ctx ->
                recurringInvoices.findByTenantIdAndId(ctx.tenantId(), recurringInvoiceId)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "RecurringInvoice not found", 3605, 404)))
                        .flatMap(this::spawnDueFor));
    }

    /**
     * Spawn due occurrences for one template, swallowing per-template errors (a
     * malformed RRULE → existing 1300, here logged-and-skipped).
     */
    private Mono<Void> spawnDueForSafe(RecurringInvoice ri) {
        return spawnDueFor(ri)
                .onErrorResume(ex -> {
                    log.warn("RecurringInvoiceSpawnService skipped recurring invoice {}: {}",
                            ri.getId(), ex.toString());
                    return Mono.empty();
                });
    }

    Mono<Void> spawnDueFor(RecurringInvoice ri) {
        if (ri.getStatus() != Status.ACTIVE) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(ri.getTenantId(), null, Set.of(SYSTEM_ROLE));
        Instant now = clock.instant();
        // Window: occurrences strictly after the last spawned period, up to and
        // including now. cursorFrom EXCLUDES lastRunAt (already spawned); on the
        // first run seedAt.minusMillis(1) makes the seed occurrence itself eligible.
        // ical4j getDates(seed, from, to) is inclusive on both ends, so `to = now`
        // captures an occurrence exactly at now WITHOUT billing any future period
        // early (the money-conservative reading; the ledger makes it idempotent).
        Instant cursorFrom = ri.getLastRunAt() != null
                ? ri.getLastRunAt()
                : ri.getSeedAt().minusMillis(1);

        return Mono.fromCallable(() ->
                        recurringSchedule.expand(ri.getRrule(), ri.getSeedAt(), cursorFrom, now))
                .flatMap(due -> {
                    if (due.isEmpty()) {
                        return Mono.empty();
                    }
                    // Ascending; bounded catch-up — one invoice per missed period,
                    // earliest first, capped at maxCatchup.
                    List<Instant> bounded = due.stream()
                            .sorted()
                            .limit(Math.max(0, maxCatchup))
                            .toList();
                    return Flux.fromIterable(bounded)
                            // concatMap (sequential) so occurrenceCount / lastRunAt /
                            // nextRunAt advance monotonically and deterministically.
                            .concatMap(occ -> spawnOneIfUnseen(ri, occ))
                            .then();
                })
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /**
     * The explicit-boolean occurrence probe (E-D3 / §9 item 3). NOT
     * {@code switchIfEmpty(doSpawn)} — the probe is mapped to a boolean and
     * branched; an already-seen period is a no-op.
     */
    private Mono<Void> spawnOneIfUnseen(RecurringInvoice ri, Instant occ) {
        String periodKey = occ.toString();
        return occurrences.findByTenantIdAndRecurringInvoiceIdAndPeriodKey(
                        ri.getTenantId(), ri.getId(), periodKey)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> Boolean.TRUE.equals(seen)
                        ? Mono.empty()
                        : doSpawn(ri, occ, periodKey));
    }

    /**
     * Ledger-insert FIRST (E-D3 step 4). Order is load-bearing:
     * <ol>
     *   <li>insert the {@link RecurringInvoiceOccurrence} (spawnedInvoiceId=null) —
     *       a {@link DuplicateKeyException} means another fire owns this period ⇒
     *       {@code Mono.empty()} (ZERO invoice, ZERO double-bill, ZERO orphan DRAFT);</li>
     *   <li>create the DRAFT invoice via the existing {@code InvoiceService.create};</li>
     *   <li>back-fill {@code spawnedInvoiceId} + advance the parent
     *       (lastRunAt / lastSpawnedInvoiceId / occurrenceCount++ / nextRunAt — null
     *       or endAt-reached ⇒ ENDED) + publish RECURRING_INVOICE_SPAWNED;</li>
     *   <li>if {@code autoFinalize}, transition the invoice DRAFT→SENT (fires
     *       INVOICE_FINALIZED → the shipped QuickBooksInvoiceSync).</li>
     * </ol>
     */
    private Mono<Void> doSpawn(RecurringInvoice ri, Instant occ, String periodKey) {
        RecurringInvoiceOccurrence ledger = RecurringInvoiceOccurrence.builder()
                .tenantId(ri.getTenantId())
                .recurringInvoiceId(ri.getId())
                .periodKey(periodKey)
                .spawnedInvoiceId(null)
                .spawnedAt(clock.instant())
                .build();

        return occurrences.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    // Another concurrent fire owns this period — zero work, no error.
                    log.debug("Recurring invoice {} period {} already owned by another "
                            + "fire — skipping (idempotent)", ri.getId(), periodKey);
                    return Mono.empty();
                })
                .flatMap(savedLedger -> {
                    Invoice draft = Invoice.builder()
                            .tenantId(ri.getTenantId())
                            .contactId(ri.getContactId())
                            .companyId(ri.getCompanyId())
                            .dealId(ri.getDealId())
                            .projectId(ri.getProjectId())
                            .currency(ri.getCurrency())
                            // Deep-copy the template lines so future template edits
                            // don't mutate already-spawned invoices.
                            .lineItems(ri.getLineItems().stream()
                                    .map(li -> li.toBuilder().build())
                                    .toList())
                            .paymentTerms(ri.getPaymentTerms())
                            .status(Invoice.Status.DRAFT)
                            .build();

                    return invoiceService.create(draft)
                            .flatMap(created -> {
                                savedLedger.setSpawnedInvoiceId(created.getId());
                                return occurrences.save(savedLedger)
                                        .then(advanceParent(ri, occ, created.getId()))
                                        .then(maybeAutoFinalize(ri, created))
                                        .then(publishSpawned(ri, created, periodKey));
                            });
                });
    }

    /**
     * Advances the parent cursor after a successful spawn: lastRunAt, monotonic
     * lastSpawnedInvoiceId, occurrenceCount++, and the next nextRunAt (first
     * occurrence strictly after this one — {@code occ.plusMillis(1)} so {@code occ}
     * itself is excluded). No further occurrence, or endAt reached ⇒ ENDED.
     */
    private Mono<Void> advanceParent(RecurringInvoice ri, Instant occ, UUID createdInvoiceId) {
        return recurringInvoices.findByTenantIdAndId(ri.getTenantId(), ri.getId())
                .flatMap(fresh -> Mono.fromCallable(() ->
                                recurringSchedule.next(fresh.getRrule(), fresh.getSeedAt(),
                                        occ.plusMillis(1)).orElse(null))
                        .flatMap(next -> {
                            fresh.setLastRunAt(occ);
                            fresh.setLastSpawnedInvoiceId(createdInvoiceId);
                            fresh.setOccurrenceCount(fresh.getOccurrenceCount() + 1);
                            boolean endReached = fresh.getEndAt() != null
                                    && !occ.isBefore(fresh.getEndAt());
                            if (next == null || endReached) {
                                fresh.setNextRunAt(null);
                                fresh.setStatus(Status.ENDED);
                            } else {
                                fresh.setNextRunAt(next);
                            }
                            return recurringInvoices.save(fresh)
                                    .flatMap(saved -> {
                                        if (saved.getStatus() == Status.ENDED) {
                                            Map<String, Object> p = new HashMap<>();
                                            p.put("recurringInvoiceId", saved.getId().toString());
                                            p.put("reason", endReached
                                                    ? "endAt reached" : "no further occurrence");
                                            events.publish(DomainEvent.of(
                                                    DomainEventType.RECURRING_INVOICE_ENDED,
                                                    saved.getTenantId(), saved.getId(), p));
                                        }
                                        return Mono.empty();
                                    });
                        }));
    }

    private Mono<Void> maybeAutoFinalize(RecurringInvoice ri, Invoice created) {
        if (!ri.isAutoFinalize()) {
            return Mono.empty();
        }
        // setStatus(SENT) fires the DRAFT→SENT edge → INVOICE_FINALIZED →
        // the shipped QuickBooksInvoiceSync (unchanged).
        return invoiceService.setStatus(created.getId(), Invoice.Status.SENT).then();
    }

    private Mono<Void> publishSpawned(RecurringInvoice ri, Invoice created, String periodKey) {
        return recurringInvoices.findByTenantIdAndId(ri.getTenantId(), ri.getId())
                .flatMap(fresh -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("recurringInvoiceId", ri.getId().toString());
                    payload.put("spawnedInvoiceId", created.getId().toString());
                    payload.put("periodKey", periodKey);
                    payload.put("occurrenceCount", fresh.getOccurrenceCount());
                    events.publish(DomainEvent.of(
                            DomainEventType.RECURRING_INVOICE_SPAWNED,
                            ri.getTenantId(), ri.getId(), payload));
                    return Mono.<Void>empty();
                });
    }
}
