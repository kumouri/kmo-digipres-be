package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "Get Paid" AR / collections aging sweep (band 4600-4619). A default-OFF scheduled per-tenant
 * sweep that (1) transitions past-due {@code SENT} invoices to {@code OVERDUE} and (2) emits the tiered
 * advisory {@code INVOICE_OVERDUE_{D3,D7,D14}} domain events that the AR-3 dunning rules fire on. A
 * faithful structural clone of the shipped {@code CoverageNudgeJob} (default-OFF + explicit-boolean
 * ledger-probe + ledger-insert-FIRST + {@code onErrorResume(DuplicateKeyException → empty)} + synthetic
 * {@code TenantContext} {@code contextWrite} + a {@code @Scheduled} tick that subscribes on the
 * scheduler thread + a visible-for-test {@link #sweepDueOnce()} the IT drives deterministically).
 *
 * <h2>DEFAULT-OFF (a money / customer-facing-comms module — no live effect in CI / any default run)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.ar", name="enabled", matchIfMissing=false)} — the
 * bean is not even created unless a deployment explicitly opts in (the {@code GbpReviewPoller} /
 * {@code CoverageNudgeJob} / {@code NurtureRunner} precedent), so a non-AR tenant gets no SENT→OVERDUE
 * transition and no dunning event — byte-identical to before this module existed. Carries the SAME
 * {@code kmosf.modules.ar} gate as {@link ArAutoConfiguration} so the whole module flips together.
 *
 * <h2>Idempotent per (invoice, tier) — explicit-boolean probe + ledger-insert-FIRST, NEVER
 * {@code switchIfEmpty(create)}</h2>
 * For each crossed tier of an overdue invoice the sweep runs an <strong>explicit-boolean</strong>
 * {@link DunningLog} probe ({@code findBy…Tier(...).map(e->true).defaultIfEmpty(false)}); only when not
 * yet recorded does it <strong>insert the {@link DunningLog} row FIRST</strong> (unique
 * {@code tenant_invoice_tier_idx}, with {@code onErrorResume(DuplicateKeyException → empty)} as the
 * concurrent-re-run backstop) and THEN — on the first successful tier-insert for an invoice still in
 * {@code SENT} — flip it {@code SENT→OVERDUE} via the <strong>unchanged</strong>
 * {@link InvoiceService#setStatus} (no number burn, no {@code INVOICE_FINALIZED}: that edge only fires
 * on DRAFT→SENT) and emit the matching {@code INVOICE_OVERDUE_DX} event. A restart / a second tick fires
 * ZERO duplicate transition / event. <strong>Never</strong> {@code switchIfEmpty(create)} (the trap).
 *
 * <h2>Deterministic clock</h2>
 * {@code daysOverdue} is computed against an injected {@link Clock} (an {@code ObjectProvider<Clock>} so
 * a test can supply a {@code @Primary Clock.fixed(...)} — the {@code NurtureRunner} pattern; production
 * has no {@code Clock} bean ⇒ {@code Clock.systemUTC()}). {@code Invoice.dueAt} is a {@link LocalDate};
 * overdue days = whole calendar days between {@code dueAt} and today (UTC).
 *
 * <h2>Reactive + blocking-I/O</h2>
 * The {@code @Scheduled} method runs on Spring's scheduler thread pool (never the Netty event loop) and
 * subscribes the reactive chain there; the visible-for-test {@link #sweepDueOnce()} returns a
 * {@code Mono<Void>} the IT blocks. No live external call anywhere in this sweep (the SENT→OVERDUE
 * transition + the advisory event are the only effects; the dunning sends are AR-3).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kmosf.modules.ar", name = "enabled", matchIfMissing = false)
public class ArAgingSweepJob {

    public static final String SYSTEM_ROLE = "AUTOMATION_AR_DUNNING";

    /** The candidate statuses the per-tenant query selects: still collectible + already overdue. */
    private static final List<Invoice.Status> CANDIDATE_STATUSES =
            List.of(Invoice.Status.SENT, Invoice.Status.OVERDUE);

    private final TenantRepository tenants;
    private final InvoiceRepository invoices;
    private final DunningLogRepository dunningLogs;
    private final InvoiceService invoiceService;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final long graceDays;

    public ArAgingSweepJob(
            TenantRepository tenants,
            InvoiceRepository invoices,
            DunningLogRepository dunningLogs,
            InvoiceService invoiceService,
            DomainEventPublisher events,
            ObjectProvider<Clock> clockProvider,
            @Value("${kmosf.modules.ar.grace-days:0}") long graceDays) {
        this.tenants = tenants;
        this.invoices = invoices;
        this.dunningLogs = dunningLogs;
        this.invoiceService = invoiceService;
        this.events = events;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.graceDays = Math.max(0, graceDays);
    }

    /**
     * Scheduled tick — fixed delay, default 24h ({@code kmosf.modules.ar.interval-ms}). Fire-and-forget
     * subscribe on the scheduler thread (never the Netty loop); the per-tenant pipeline catches + logs
     * so one tenant's failure never aborts the rest.
     */
    @Scheduled(
            fixedDelayString = "${kmosf.modules.ar.interval-ms:86400000}",
            initialDelayString = "${kmosf.modules.ar.initial-delay-ms:60000}")
    public void scheduledTick() {
        sweepDueOnce().subscribe(
                ignored -> {},
                err -> log.error("ArAgingSweepJob tick failed", err));
    }

    /**
     * Visible-for-test entry — runs one full aging sweep across all tenants and returns when done, so an
     * IT can drive it deterministically (the {@code CoverageNudgeJob.nudgeDueOnce} pattern).
     */
    public Mono<Void> sweepDueOnce() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return tenants.findAll()
                .concatMap(t -> sweepTenant(t.getId(), today)
                        .onErrorResume(err -> {
                            log.warn("AR aging sweep failed for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sweepTenant(UUID tenantId, LocalDate today) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of(SYSTEM_ROLE));
        return invoices.findAllByTenantIdAndStatusIn(tenantId, CANDIDATE_STATUSES)
                .concatMap(invoice -> sweepInvoice(tenantId, invoice, today)
                        .onErrorResume(err -> {
                            log.warn("AR aging sweep failed for invoice {} (tenant {}): {}",
                                    invoice.getId(), tenantId, err.toString());
                            return Mono.empty();
                        }))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /**
     * Evaluate one candidate invoice: compute {@code daysOverdue} (past {@code dueAt} + grace) and the
     * set of crossed tiers, then process each crossed tier in ascending order through the
     * ledger-insert-FIRST seam. An invoice not yet past its grace window crosses no tier → no-op.
     */
    private Mono<Void> sweepInvoice(UUID tenantId, Invoice invoice, LocalDate today) {
        LocalDate dueAt = invoice.getDueAt();
        if (dueAt == null) {
            return Mono.empty();
        }
        long daysOverdue = ChronoUnit.DAYS.between(dueAt, today) - graceDays;
        List<DunningLog.DunningTier> crossed = crossedTiers(daysOverdue);
        if (crossed.isEmpty()) {
            return Mono.empty();
        }
        // Sequentially fold the crossed tiers, carrying whether THIS sweep has already flipped the
        // invoice SENT→OVERDUE (so only the first newly-recorded tier triggers the single flip; D7/D14
        // in the same pass see the already-flipped flag).
        SweepState state = new SweepState(invoice.getStatus() == Invoice.Status.SENT);
        return Flux.fromIterable(crossed)
                .concatMap(tier -> processTier(tenantId, invoice, tier, daysOverdue, state))
                .then();
    }

    /**
     * Explicit-boolean probe → ledger-insert-FIRST → (first-insert-only) flip SENT→OVERDUE → emit. The
     * unique {@code tenant_invoice_tier_idx} backstops a concurrent re-run via
     * {@code onErrorResume(DuplicateKeyException → empty)} = zero duplicate transition/event. NEVER
     * {@code switchIfEmpty(create)}.
     */
    private Mono<Void> processTier(UUID tenantId, Invoice invoice, DunningLog.DunningTier tier,
                                   long daysOverdue, SweepState state) {
        return dunningLogs.findByTenantIdAndInvoiceIdAndTier(tenantId, invoice.getId(), tier)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(alreadyRecorded -> {
                    if (alreadyRecorded) {
                        return Mono.empty();
                    }
                    return insertLedgerThenAct(tenantId, invoice, tier, daysOverdue, state);
                });
    }

    private Mono<Void> insertLedgerThenAct(UUID tenantId, Invoice invoice, DunningLog.DunningTier tier,
                                           long daysOverdue, SweepState state) {
        DunningLog row = DunningLog.builder()
                .tenantId(tenantId)
                .invoiceId(invoice.getId())
                .tier(tier)
                .contactId(invoice.getContactId())
                .sentAt(clock.instant())
                .build();
        return dunningLogs.save(row)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("AR dunning concurrent-fire lost ledger insert for invoice {} tier {} "
                            + "— zero duplicate", invoice.getId(), tier);
                    return Mono.empty();
                })
                // save() emits the saved row only when the insert won (empty on a lost concurrent race),
                // so the flip + emit run exactly once per (invoice, tier).
                .flatMap(saved -> flipToOverdueIfFirst(invoice, state)
                        .then(Mono.fromRunnable(() ->
                                emitOverdue(tenantId, invoice, tier, daysOverdue))));
    }

    /**
     * On the FIRST newly-recorded tier of an invoice still in SENT, flip it SENT→OVERDUE via the
     * unchanged {@link InvoiceService#setStatus}. Subsequent tiers in the same pass (state already
     * flipped) — and any invoice that entered the sweep already OVERDUE — skip the flip.
     */
    private Mono<Void> flipToOverdueIfFirst(Invoice invoice, SweepState state) {
        if (!state.needsFlip) {
            return Mono.empty();
        }
        state.needsFlip = false;
        return invoiceService.setStatus(invoice.getId(), Invoice.Status.OVERDUE).then();
    }

    private void emitOverdue(UUID tenantId, Invoice invoice, DunningLog.DunningTier tier,
                             long daysOverdue) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoice.getId().toString());
        if (invoice.getContactId() != null) {
            payload.put("contactId", invoice.getContactId().toString());
        }
        payload.put("daysOverdue", daysOverdue);
        BigDecimal balance = invoice.getBalance() != null ? invoice.getBalance() : invoice.getTotal();
        payload.put("balance", balance);
        payload.put("currency", invoice.getCurrency());
        events.publish(DomainEvent.of(eventType(tier), tenantId, invoice.getId(), payload));
    }

    /** The tiers crossed at a given (grace-adjusted) days-overdue: ≥3→D3, ≥7→D7, ≥14→D14. */
    private static List<DunningLog.DunningTier> crossedTiers(long daysOverdue) {
        List<DunningLog.DunningTier> out = new ArrayList<>(3);
        if (daysOverdue >= 3) out.add(DunningLog.DunningTier.D3);
        if (daysOverdue >= 7) out.add(DunningLog.DunningTier.D7);
        if (daysOverdue >= 14) out.add(DunningLog.DunningTier.D14);
        return out;
    }

    private static String eventType(DunningLog.DunningTier tier) {
        return switch (tier) {
            case D3 -> DomainEventType.INVOICE_OVERDUE_D3;
            case D7 -> DomainEventType.INVOICE_OVERDUE_D7;
            case D14 -> DomainEventType.INVOICE_OVERDUE_D14;
        };
    }

    /** Per-invoice mutable carry across the sequential tier fold (single-threaded within one invoice). */
    private static final class SweepState {
        private boolean needsFlip;

        private SweepState(boolean needsFlip) {
            this.needsFlip = needsFlip;
        }
    }
}
