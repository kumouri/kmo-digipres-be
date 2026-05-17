package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoiceOccurrence;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.recurring.RecurringInvoiceOccurrenceRepository;
import com.kumouri.kmodigipresbe.repository.recurring.RecurringInvoiceRepository;
import com.kumouri.kmodigipresbe.service.recurring.RecurringInvoiceSpawnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the E-D3 {@code occurrenceCount} drift (fix branch
 * {@code fix/recurring-occurrence-count-atomic}).
 *
 * <p>The denormalized {@link RecurringInvoice#getOccurrenceCount()} formerly drifted
 * off-by-one under a concurrent spawn because {@code advanceParent} did a non-atomic
 * reactive read-modify-write of the {@code @Version}-locked counter (plus a second
 * re-read in {@code publishSpawned}). The authoritative unique-indexed
 * {@link RecurringInvoiceOccurrence} ledger was always exactly correct; only the
 * counter drifted. The fix makes the advance ONE atomic {@code findAndModify}
 * ({@code $inc occurrenceCount}/{@code $inc version} + {@code $set} cursor), reached
 * exactly once per successfully-completed {@code doSpawn}, with nothing
 * failure-prone after it (the {@code doSpawn} reorder).
 *
 * <h2>Why this spec's exact assertion is foreign-tick-IMMUNE (and
 * {@code RecurringInvoiceSpawnRestartIT}'s deliberately is NOT)</h2>
 * {@code occurrenceCount} lives on the parent document; the ledger rows are
 * separate documents. ANY {@code occurrenceCount == ledger-count} check is therefore
 * a <strong>two-read cross-document comparison</strong>. On the shared singleton
 * Testcontainers Mongo a foreign cross-tenant {@code runDueOnce()} (a sibling
 * spec's explicit call — {@code findAllDueAcrossTenants} is intentionally
 * cross-tenant) can advance an <em>ACTIVE, still-due</em> template <em>between</em>
 * the two reads, skewing an exact equality. That is exactly why
 * {@code RecurringInvoiceSpawnRestartIT} (an open-ended, perpetually-due DAILY
 * template) keeps a deliberately loose foreign-tick-immune {@code occurrenceCount}
 * sanity check and forbids exact cross-read totals — re-confirmed by a full-suite
 * failure when that was (wrongly) tightened.
 *
 * <p>This spec instead drives the template to a <strong>terminal {@code ENDED}
 * state</strong> ({@code FREQ=DAILY;COUNT=3} ⇒ exactly 3 periods, then the RRULE is
 * exhausted ⇒ {@code status=ENDED}). {@code findAllDueAcrossTenants} filters
 * {@code status:'ACTIVE'}, so an {@code ENDED} template is <strong>never re-scanned
 * by any tick — foreign or otherwise — and is permanently immutable</strong>. The
 * unique {@code tenant_recurring_period_idx} + {@code COUNT=3} cap the ledger at
 * exactly 3 rows no matter <em>which</em> tick spawned each period. So at the
 * terminal state both reads observe a frozen, deterministic
 * {@code occurrenceCount == completed-ledger-rows == 3} — bulletproof in isolation
 * AND in the full suite, with zero read-skew window. This is the deterministic
 * exact proof of the fix; {@code RecurringInvoiceSpawnRestartIT} proves the
 * money invariant under perpetual catch-up.
 *
 * <p>Config mirrors {@code RecurringInvoiceSpawnRestartIT} (fixed {@code @Primary}
 * Clock; global spawn-job disabled; {@code max-catchup=2} so reaching the terminal
 * state genuinely requires bounded multi-tick catch-up: 2 then 1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, RecurringInvoiceOccurrenceCountIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.recurring-invoice.max-catchup=2"
})
class RecurringInvoiceOccurrenceCountIT {

    static final Instant FIXED_NOW = Instant.parse("2026-05-14T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired RecurringInvoiceRepository recurringInvoices;
    @Autowired RecurringInvoiceOccurrenceRepository occurrences;
    @Autowired RecurringInvoiceSpawnService spawnService;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), RecurringInvoice.class).block();
        mongo.remove(new Query(), RecurringInvoiceOccurrence.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-occ-count-" + tenantId)
                .displayName("RI OccurrenceCount Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
    }

    /** A FRESH, finite (COUNT-bounded) template: no prior run, occurrenceCount 0. */
    private RecurringInvoice seedFresh(String rrule, Instant seedAt) {
        RecurringInvoice ri = RecurringInvoice.builder()
                .tenantId(tenantId)
                .templateName("OccurrenceCount template")
                .rrule(rrule)
                .seedAt(seedAt)
                .lastRunAt(null)
                .nextRunAt(seedAt) // due
                .occurrenceCount(0)
                .status(RecurringInvoice.Status.ACTIVE)
                .paymentTerms(Invoice.PaymentTerms.NET_30)
                .lineItems(List.of(LineItem.builder()
                        .description("Service")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("100.00"))
                        .discountPercent(BigDecimal.ZERO)
                        .taxPercent(BigDecimal.ZERO)
                        .build()))
                .build();
        return recurringInvoices.save(ri).block();
    }

    private List<RecurringInvoiceOccurrence> ledgerFor(UUID recurringInvoiceId) {
        return occurrences
                .findAllByTenantIdAndRecurringInvoiceId(tenantId, recurringInvoiceId)
                .collectList()
                .block();
    }

    private long completedLedgerRows(UUID recurringInvoiceId) {
        return ledgerFor(recurringInvoiceId).stream()
                .filter(o -> o.getSpawnedInvoiceId() != null)
                .count();
    }

    private List<Invoice> invoicesFor(UUID recurringInvoiceId) {
        List<UUID> ids = ledgerFor(recurringInvoiceId).stream()
                .map(RecurringInvoiceOccurrence::getSpawnedInvoiceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return List.of();
        }
        return mongo.find(Query.query(Criteria.where("_id").in(ids)), Invoice.class)
                .collectList()
                .block();
    }

    /**
     * Foreign-tick-immune money invariant (single-snapshot ledger-internal
     * consistency): every completed period bills exactly once — distinct
     * periodKeys ↔ distinct invoice ids ↔ DRAFT $100 null-numbered invoices.
     */
    private void assertExactlyOneInvoicePerPeriod(UUID recurringInvoiceId) {
        List<RecurringInvoiceOccurrence> completed = ledgerFor(recurringInvoiceId).stream()
                .filter(o -> o.getSpawnedInvoiceId() != null)
                .toList();
        long distinctPeriods = completed.stream()
                .map(RecurringInvoiceOccurrence::getPeriodKey).distinct().count();
        assertThat((long) completed.size()).isEqualTo(distinctPeriods);
        long distinctInvoiceIds = completed.stream()
                .map(RecurringInvoiceOccurrence::getSpawnedInvoiceId).distinct().count();
        assertThat(distinctInvoiceIds).isEqualTo(distinctPeriods);
        List<Invoice> invoices = invoicesFor(recurringInvoiceId);
        assertThat((long) invoices.size()).isEqualTo(distinctInvoiceIds);
        invoices.forEach(i -> {
            assertThat(i.getStatus()).isEqualTo(Invoice.Status.DRAFT);
            assertThat(i.getTotal()).isEqualByComparingTo("100.00");
            assertThat(i.getInvoiceNumber()).isNull();
        });
    }

    /**
     * THE regression, asserted ONLY at the immutable terminal {@code ENDED} state.
     * A {@code FREQ=DAILY;COUNT=3} template seeded fresh 10 days in the past has
     * exactly 3 due periods; with {@code max-catchup=2} it takes ≥2 ticks to
     * materialize all 3, after which the RRULE is exhausted and the parent
     * transitions to {@code ENDED}. From then on it is never re-scanned by any tick
     * (the cross-tenant due-scan filters {@code status:'ACTIVE'}) and the
     * {@code COUNT=3} + unique period index cap the ledger at exactly 3 — so
     * {@code occurrenceCount == completed-ledger-rows == 3} EXACTLY, with no
     * read-skew window (the state is frozen between the two reads), regardless of
     * any foreign cross-tenant tick on the shared Mongo. The former non-atomic
     * read-modify-write would intermittently land this at 2 (a lost increment).
     */
    @Test
    void finiteTemplate_caughtUpToEnded_occurrenceCountExactlyEqualsLedgerRowCount() {
        Instant seedAt = FIXED_NOW.minus(10, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        RecurringInvoice ri = seedFresh("FREQ=DAILY;COUNT=3", seedAt);
        assertThat(ri.getOccurrenceCount()).isZero();

        // Bounded multi-tick catch-up (max-catchup=2 ⇒ 2 then 1) drives the
        // finite template to its terminal ENDED state. Extra ticks are no-ops
        // (ENDED ⇒ not ACTIVE ⇒ never returned by the due-scan). The per-tick
        // money invariant is foreign-tick-immune and is checked each tick; the
        // EXACT counter==ledger equality is asserted ONLY at the frozen terminal
        // state below (a mid-catch-up exact check would be cross-read-skew-fragile
        // while the template is still ACTIVE+due — see class Javadoc).
        for (int tick = 0; tick < 4; tick++) {
            spawnService.runDueOnce().block();
            assertExactlyOneInvoicePerPeriod(ri.getId());
        }

        RecurringInvoice after = recurringInvoices
                .findByTenantIdAndId(tenantId, ri.getId()).block();
        assertThat(after).isNotNull();
        // Terminal, immutable: RRULE exhausted (COUNT=3) ⇒ ENDED, cursor cleared.
        assertThat(after.getStatus()).isEqualTo(RecurringInvoice.Status.ENDED);
        assertThat(after.getNextRunAt()).isNull();
        assertThat(after.getLastRunAt()).isAfter(seedAt);

        // The exact, drift-proof, foreign-tick-immune invariant — the E-D3 fix:
        // exactly 3 periods billed, counter exactly tracks the ledger, no off-by-one.
        assertThat(completedLedgerRows(ri.getId()))
                .as("COUNT=3 ⇒ exactly 3 completed occurrence-ledger rows")
                .isEqualTo(3L);
        assertThat((long) after.getOccurrenceCount())
                .as("occurrenceCount must EXACTLY equal the completed occurrence-"
                        + "ledger row count (atomic $inc, frozen terminal state) — "
                        + "the E-D3 drift fix")
                .isEqualTo(completedLedgerRows(ri.getId()));
        assertThat(after.getOccurrenceCount()).isEqualTo(3);
        assertExactlyOneInvoicePerPeriod(ri.getId());
    }
}
