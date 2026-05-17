package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoiceOccurrence;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.recurring.RecurringInvoiceRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-E2 (HEADLINE) — a monthly RRULE spawns exactly ONE Invoice per cadence
 * period, idempotent under a re-fire (Quartz re-fire / misfire / restart sim).
 *
 * <p>The two spawn calls use <strong>different</strong> Idempotency-Keys on
 * purpose: a same-key replay would only exercise the idempotency FILTER. Using
 * different keys forces BOTH requests through {@code RecurringInvoiceSpawnService}
 * — the {@code RecurringInvoiceOccurrence} unique-indexed ledger (ledger-insert
 * FIRST) is what must then guarantee exactly one invoice + one ledger row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class RecurringInvoiceSpawnIdempotencyIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired RecurringInvoiceRepository recurringInvoices;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private String token;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), RecurringInvoice.class).block();
        mongo.remove(new Query(), RecurringInvoiceOccurrence.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-idem-" + tenantId)
                .displayName("RI Idempotency Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@riidem.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private RecurringInvoice seedActiveMonthly(boolean autoFinalize) {
        // seedAt ~1 day ago: a monthly RRULE then has EXACTLY ONE occurrence in
        // the (seedAt-1ms, now] window (the next monthly occurrence is ~1 month
        // out, beyond now) — deterministic "exactly one invoice this tick".
        Instant seedAt = Instant.now().minus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        RecurringInvoice ri = RecurringInvoice.builder()
                .tenantId(tenantId)
                .templateName("Monthly 500")
                .rrule("FREQ=MONTHLY")
                .seedAt(seedAt)
                .nextRunAt(seedAt) // due now
                .status(RecurringInvoice.Status.ACTIVE)
                .autoFinalize(autoFinalize)
                .paymentTerms(Invoice.PaymentTerms.NET_30)
                .lineItems(List.of(LineItem.builder()
                        .description("Monthly service")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("500.00"))
                        .discountPercent(BigDecimal.ZERO)
                        .taxPercent(BigDecimal.ZERO)
                        .build()))
                .build();
        return recurringInvoices.save(ri).block();
    }

    private void spawnNow(UUID riId, String idempotencyKey) {
        web.post().uri("/recurring-invoices/" + riId + "/spawn-now")
                .header("Authorization", token)
                .header("Idempotency-Key", idempotencyKey)
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    void spawnTwice_sameCadencePeriod_yieldsExactlyOneInvoiceAndOneLedgerRow() {
        RecurringInvoice ri = seedActiveMonthly(false);

        // First spawn (key k1)
        spawnNow(ri.getId(), UUID.randomUUID().toString());

        // Exactly ONE DRAFT invoice, total 500.00 (through InvoiceService.create
        // -> Quote.computeTotals()), NO INVOICE_FINALIZED (autoFinalize=false).
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Invoice> invs = mongo.findAll(Invoice.class).collectList().block();
            assertThat(invs).hasSize(1);
        });
        List<Invoice> invoices = mongo.findAll(Invoice.class).collectList().block();
        Invoice spawned = invoices.get(0);
        assertThat(spawned.getStatus()).isEqualTo(Invoice.Status.DRAFT);
        assertThat(spawned.getTotal()).isEqualByComparingTo("500.00");
        assertThat(spawned.getPaymentTerms()).isEqualTo(Invoice.PaymentTerms.NET_30);
        assertThat(observed).noneMatch(e ->
                DomainEventType.INVOICE_FINALIZED.equals(e.type()));

        // Exactly ONE occurrence ledger row, periodKey set, spawnedInvoiceId set.
        List<RecurringInvoiceOccurrence> occ1 =
                mongo.findAll(RecurringInvoiceOccurrence.class).collectList().block();
        assertThat(occ1).hasSize(1);
        assertThat(occ1.get(0).getPeriodKey()).isNotBlank();
        assertThat(occ1.get(0).getSpawnedInvoiceId()).isEqualTo(spawned.getId());

        // Parent advanced: lastRunAt set, occurrenceCount=1, nextRunAt ~1 month
        // out, still ACTIVE.
        RecurringInvoice afterFirst = recurringInvoices
                .findByTenantIdAndId(tenantId, ri.getId()).block();
        assertThat(afterFirst.getOccurrenceCount()).isEqualTo(1);
        assertThat(afterFirst.getLastRunAt()).isNotNull();
        assertThat(afterFirst.getStatus()).isEqualTo(RecurringInvoice.Status.ACTIVE);
        assertThat(afterFirst.getNextRunAt())
                .isAfter(Instant.now().plus(20, ChronoUnit.DAYS));

        // SECOND spawn for the SAME period — different key (Quartz re-fire sim).
        // The occurrence ledger must make this a no-op: still ONE invoice + ONE row.
        spawnNow(ri.getId(), UUID.randomUUID().toString());

        // Give the (idempotent) second spawn a moment, then assert no growth.
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        List<Invoice> afterSecond = mongo.findAll(Invoice.class).collectList().block();
        assertThat(afterSecond).hasSize(1);
        List<RecurringInvoiceOccurrence> occ2 =
                mongo.findAll(RecurringInvoiceOccurrence.class).collectList().block();
        assertThat(occ2).hasSize(1);
        // occurrenceCount must NOT have advanced past 1 (zero double-bill).
        RecurringInvoice afterSecondRi = recurringInvoices
                .findByTenantIdAndId(tenantId, ri.getId()).block();
        assertThat(afterSecondRi.getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    void autoFinalizeTrue_spawnsSentInvoice_andEmitsInvoiceFinalizedExactlyOnce() {
        RecurringInvoice ri = seedActiveMonthly(true);

        spawnNow(ri.getId(), UUID.randomUUID().toString());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Invoice> invs = mongo.findAll(Invoice.class).collectList().block();
            assertThat(invs).hasSize(1);
            assertThat(invs.get(0).getStatus()).isEqualTo(Invoice.Status.SENT);
        });

        // INVOICE_FINALIZED emitted exactly once (autoFinalize -> setStatus SENT).
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed.stream()
                        .filter(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type()))
                        .count()).isEqualTo(1L));

        // RECURRING_INVOICE_SPAWNED also emitted.
        assertThat(observed).anyMatch(e ->
                DomainEventType.RECURRING_INVOICE_SPAWNED.equals(e.type()));
    }
}
