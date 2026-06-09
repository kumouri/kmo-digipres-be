package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AR-2 — ArAgingSweepIT: drives the (default-OFF) AR aging sweep, enabled in-test, and proves it
 * (a) flips past-due SENT invoices to OVERDUE, (b) records exactly the crossed dunning tiers per invoice
 * (≥3→D3, ≥7→D7, ≥14→D14) as one {@code DunningLog} row each, (c) emits the matching
 * {@code INVOICE_OVERDUE_{D3,D7,D14}} advisory events, and (d) is idempotent — a second sweep at the same
 * (fixed) clock records ZERO new rows and emits ZERO new events. Mirrors {@code CoverageNudgeIT}
 * (default-OFF sweep enabled in-test, deterministic {@code sweepDueOnce()} block, scheduled tick pushed
 * far out) + {@code NurtureRunnerIT} (a {@code @Primary Clock.fixed(...)} for a deterministic now).
 *
 * <h2>§7</h2>
 * The sweep is default-OFF in prod/CI ({@code matchIfMissing=false}); this IT explicitly opts in via
 * {@code kmosf.modules.ar.enabled=true}. The only effects are the SENT→OVERDUE transition (the unchanged
 * {@code InvoiceService.setStatus}) and the advisory event — no live external call anywhere. The
 * scheduled trigger's initial delay is pushed far out so the {@code @Scheduled} tick never races the
 * deterministic {@code sweepDueOnce()}.
 *
 * <p>Shard-safe: no {@code @MockBean}; self-clean {@code mongo.remove} {@code @BeforeEach}; no
 * {@code application-test.properties} / {@code build.gradle} shard change.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ArAgingSweepIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.ar.enabled=true",
        // Push the @Scheduled tick far out so only the explicit sweepDueOnce() runs in-test.
        "kmosf.modules.ar.initial-delay-ms=3600000",
        "kmosf.modules.ar.interval-ms=3600000"
})
class ArAgingSweepIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ArAgingSweepJob sweepJob;
    @Autowired DunningLogRepository dunningLogs;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private UUID inv4d;   // 4 days overdue  → D3
    private UUID inv8d;   // 8 days overdue  → D3 + D7
    private UUID inv15d;  // 15 days overdue → D3 + D7 + D14
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), DunningLog.class).block();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("ar-aging-it-" + tenantId)
                .displayName("AR Aging IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        inv4d = seedSentInvoice("INV-2026-0001", TODAY.minusDays(4));
        inv8d = seedSentInvoice("INV-2026-0002", TODAY.minusDays(8));
        inv15d = seedSentInvoice("INV-2026-0003", TODAY.minusDays(15));

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    /** A SENT, numbered invoice with a contact, a $100 balance, and the given LocalDate dueAt. */
    private UUID seedSentInvoice(String number, LocalDate dueAt) {
        UUID id = UUID.randomUUID();
        mongo.save(Invoice.builder()
                .id(id).tenantId(tenantId)
                .invoiceNumber(number)
                .status(Invoice.Status.SENT)
                .contactId(UUID.randomUUID())
                .currency("USD")
                .lineItems(List.of())
                .subtotal(new BigDecimal("100.00"))
                .total(new BigDecimal("100.00"))
                .balance(new BigDecimal("100.00"))
                .issuedAt(dueAt.minusDays(30))
                .dueAt(dueAt)
                .statusChangedAt(NOW)
                .build()).block();
        return id;
    }

    private Invoice load(UUID id) {
        return mongo.findById(id, Invoice.class).block();
    }

    private List<DunningLog.DunningTier> tiersFor(UUID invoiceId) {
        return mongo.find(new Query(Criteria.where("invoiceId").is(invoiceId)), DunningLog.class)
                .map(DunningLog::getTier)
                .collectList().block();
    }

    private long overdueEventCount(String type, UUID invoiceId) {
        return observed.stream()
                .filter(e -> type.equals(e.type()) && invoiceId.equals(e.subjectId()))
                .count();
    }

    @Test
    void flipsSentToOverdue_recordsCrossedTiers_emitsEvents_andIsIdempotent() {
        // First sweep — flips all three to OVERDUE and records each invoice's crossed tiers.
        sweepJob.sweepDueOnce().block();

        // (a) all three SENT invoices flipped to OVERDUE.
        assertThat(load(inv4d).getStatus()).isEqualTo(Invoice.Status.OVERDUE);
        assertThat(load(inv8d).getStatus()).isEqualTo(Invoice.Status.OVERDUE);
        assertThat(load(inv15d).getStatus()).isEqualTo(Invoice.Status.OVERDUE);

        // (b) exactly the crossed tiers per invoice.
        assertThat(tiersFor(inv4d)).containsExactlyInAnyOrder(DunningLog.DunningTier.D3);
        assertThat(tiersFor(inv8d)).containsExactlyInAnyOrder(
                DunningLog.DunningTier.D3, DunningLog.DunningTier.D7);
        assertThat(tiersFor(inv15d)).containsExactlyInAnyOrder(
                DunningLog.DunningTier.D3, DunningLog.DunningTier.D7, DunningLog.DunningTier.D14);

        // Total ledger rows = 1 + 2 + 3 = 6.
        assertThat(mongo.findAll(DunningLog.class).collectList().block()).hasSize(6);

        // (c) the matching advisory events fired once each.
        assertThat(overdueEventCount(DomainEventType.INVOICE_OVERDUE_D3, inv4d)).isEqualTo(1);
        assertThat(overdueEventCount(DomainEventType.INVOICE_OVERDUE_D3, inv8d)).isEqualTo(1);
        assertThat(overdueEventCount(DomainEventType.INVOICE_OVERDUE_D7, inv8d)).isEqualTo(1);
        assertThat(overdueEventCount(DomainEventType.INVOICE_OVERDUE_D3, inv15d)).isEqualTo(1);
        assertThat(overdueEventCount(DomainEventType.INVOICE_OVERDUE_D7, inv15d)).isEqualTo(1);
        assertThat(overdueEventCount(DomainEventType.INVOICE_OVERDUE_D14, inv15d)).isEqualTo(1);
        long totalOverdueEvents = observed.stream()
                .filter(e -> e.type().startsWith("invoice.overdue"))
                .count();
        assertThat(totalOverdueEvents).isEqualTo(6);

        // (d) idempotency — a second sweep at the same fixed clock records ZERO new rows and emits
        // ZERO new events (the DunningLog ledger-insert-FIRST guard; invoices are already OVERDUE).
        observed.clear();
        sweepJob.sweepDueOnce().block();

        assertThat(mongo.findAll(DunningLog.class).collectList().block()).hasSize(6);
        assertThat(observed.stream().filter(e -> e.type().startsWith("invoice.overdue")).count())
                .isZero();
        // Still OVERDUE (no spurious re-transition).
        assertThat(load(inv4d).getStatus()).isEqualTo(Invoice.Status.OVERDUE);
        assertThat(load(inv8d).getStatus()).isEqualTo(Invoice.Status.OVERDUE);
        assertThat(load(inv15d).getStatus()).isEqualTo(Invoice.Status.OVERDUE);
    }
}
