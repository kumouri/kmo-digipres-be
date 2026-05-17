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
 * counter drifted — flagged in {@code RecurringInvoiceSpawnRestartIT}'s Javadoc as a
 * separate out-of-scope follow-up. The fix makes the advance ONE atomic
 * {@code findAndModify} ({@code $inc occurrenceCount}/{@code $inc version} +
 * {@code $set} cursor), reached exactly once per successfully-completed
 * {@code doSpawn}, with nothing failure-prone after it (the {@code doSpawn} reorder).
 *
 * <p>This spec asserts the now-guaranteed invariant after a real multi-period
 * catch-up: <strong>{@code occurrenceCount} exactly equals the completed
 * occurrence-ledger row count for the template</strong>. The template is seeded
 * <em>fresh</em> ({@code lastRunAt == null}, {@code occurrenceCount == 0}) so the
 * equality has <strong>zero seed offset</strong> — it is a direct
 * {@code count == ledger}. The assertion is ledger-RELATIVE (not a hardcoded total),
 * so — exactly like {@code RecurringInvoiceSpawnRestartIT}'s
 * {@code assertExactlyOneInvoicePerPeriod} money invariant — it is immune to a
 * foreign cross-tenant {@code runDueOnce()} tick on the shared Testcontainers Mongo:
 * both sides are driven by the same per-period atomic {@code $inc} + unique-indexed
 * ledger insert, so they advance together regardless of which tick spawned a period.
 *
 * <p>Config mirrors {@code RecurringInvoiceSpawnRestartIT} (fixed {@code @Primary}
 * Clock so the seed reference and the service's {@code now} are the same instant;
 * global spawn-job disabled; {@code max-catchup=2} so bounded multi-tick catch-up is
 * genuinely exercised).
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

    /** A FRESH template: no prior run, occurrenceCount seeded 0 (zero offset). */
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

    /** Occurrence-ledger rows for THIS template only (foreign-tick-immune). */
    private List<RecurringInvoiceOccurrence> ledgerFor(UUID recurringInvoiceId) {
        return occurrences
                .findAllByTenantIdAndRecurringInvoiceId(tenantId, recurringInvoiceId)
                .collectList()
                .block();
    }

    /**
     * Completed ledger rows (an issued invoice) for THIS template. The atomic
     * {@code $inc} happens strictly AFTER {@code spawnedInvoiceId} is back-filled,
     * so {@code occurrenceCount} counts exactly these — a foreign tick caught
     * mid-{@code doSpawn} may leave a transient {@code spawnedInvoiceId==null} row
     * that has not yet reached the {@code $inc} (excluded here, same as
     * {@code RecurringInvoiceSpawnRestartIT.assertExactlyOneInvoicePerPeriod}).
     */
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
     * The money invariant (kept alongside the counter assertion so a regression in
     * either is caught): every completed period bills exactly once — distinct
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

    /** THE regression: occurrenceCount == completed-ledger-row-count, exactly. */
    private void assertCounterEqualsLedger(UUID recurringInvoiceId) {
        RecurringInvoice after = recurringInvoices
                .findByTenantIdAndId(tenantId, recurringInvoiceId).block();
        assertThat(after).isNotNull();
        assertThat((long) after.getOccurrenceCount())
                .as("occurrenceCount must EXACTLY equal the completed occurrence-ledger "
                        + "row count (atomic $inc, zero seed offset) — the E-D3 drift fix")
                .isEqualTo(completedLedgerRows(recurringInvoiceId));
    }

    /**
     * A long-dormant fresh MONTHLY template caught up across bounded ticks: the
     * counter tracks the ledger EXACTLY at every step, and a re-run is idempotent.
     * {@code seedAt = FIXED_NOW - 75d} ⇒ 3 monthly periods due; {@code max-catchup=2}
     * ⇒ tick1 materializes 2, tick2 the 3rd, tick3 is a no-op — genuine multi-period
     * multi-tick catch-up.
     */
    @Test
    void freshMultiPeriodCatchUp_occurrenceCountExactlyEqualsLedgerRowCount() {
        Instant seedAt = FIXED_NOW.minus(75, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        RecurringInvoice ri = seedFresh("FREQ=MONTHLY", seedAt);
        assertThat(ri.getOccurrenceCount()).isZero();

        // Tick 1 — bounded catch-up makes progress; counter == ledger exactly.
        spawnService.runDueOnce().block();
        assertThat(completedLedgerRows(ri.getId())).isGreaterThanOrEqualTo(1);
        assertExactlyOneInvoicePerPeriod(ri.getId());
        assertCounterEqualsLedger(ri.getId());

        // Tick 2 — finishes the catch-up; multi-period proven (>= 2 distinct
        // periods materialized); counter still exactly == ledger.
        spawnService.runDueOnce().block();
        assertThat(completedLedgerRows(ri.getId())).isGreaterThanOrEqualTo(2);
        assertExactlyOneInvoicePerPeriod(ri.getId());
        assertCounterEqualsLedger(ri.getId());

        // Tick 3 — idempotent: no period re-billed; counter still exactly == ledger.
        long completedAfterTick2 = completedLedgerRows(ri.getId());
        spawnService.runDueOnce().block();
        assertThat(completedLedgerRows(ri.getId())).isGreaterThanOrEqualTo(completedAfterTick2);
        assertExactlyOneInvoicePerPeriod(ri.getId());
        assertCounterEqualsLedger(ri.getId());

        RecurringInvoice after = recurringInvoices
                .findByTenantIdAndId(tenantId, ri.getId()).block();
        // Cursor advanced past the seed; still ACTIVE (next monthly period is
        // future relative to the fixed clock — open-ended FREQ=MONTHLY).
        assertThat(after.getLastRunAt()).isAfter(seedAt);
        assertThat(after.getStatus()).isEqualTo(RecurringInvoice.Status.ACTIVE);
    }
}
