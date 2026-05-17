package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.service.recurring.RecurringInvoiceSpawnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invoice numbering + partial-unique {@code tenant_number_idx} — the executable
 * spec of the user-authorized resolution of the escalated blocker (Phase E,
 * E.9–E.11). Mirrors {@code ProjectCodeGeneratorIT} for the counter mechanics, and
 * additionally proves the finalize-edge idempotency + the partial index semantics
 * that unblock recurring multi-period catch-up.
 *
 * <p>House style: {@code @SpringBootTest(RANDOM_PORT)} +
 * {@code @AutoConfigureWebTestClient} + {@code @Import(TestcontainersConfiguration)}
 * + {@code kmosf.quartz.proof-job.enabled=false}; DB asserted via
 * {@code mongo.findAll}/the {@code ReactiveMongoTemplate} (the Phase-C/D
 * tenant-scoping-bypass lesson). Numbers are assigned at the DRAFT→issued edge, so
 * the counter is driven through {@code POST /invoices} (DRAFT) +
 * {@code POST /invoices/{id}/status?target=SENT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class InvoiceNumberGeneratorIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired RecurringInvoiceSpawnService spawnService;

    private UUID tenantAId;
    private UUID tenantBId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.dropCollection("invoice_number_counters").block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("inv-a-" + tenantAId)
                .displayName("INV Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("inv-b-" + tenantBId)
                .displayName("INV Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@inv.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(userA).block();
        tokenA = "Bearer " + jwt.mint(userA);

        User userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@inv.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(userB).block();
        tokenB = "Bearer " + jwt.mint(userB);
    }

    private Invoice createDraft(String token) {
        return web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Invoice.builder()
                        .lineItems(List.of(LineItem.builder()
                                .description("Service")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .discountPercent(BigDecimal.ZERO)
                                .taxPercent(BigDecimal.ZERO)
                                .build()))
                        .build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();
    }

    private Invoice setStatus(String token, UUID id, Invoice.Status target) {
        return web.post().uri("/invoices/" + id + "/status?target=" + target)
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();
    }

    /** Mirrors {@code ProjectCodeGeneratorIT.threeProjectsForTenantAreSequential}. */
    @Test
    void threeFinalizedInvoicesForTenantAreSequential() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String prefix = "INV-" + year + "-";

        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Invoice draft = createDraft(tokenA);
            assertThat(draft.getInvoiceNumber()).isNull(); // DRAFT stays null
            Invoice sent = setStatus(tokenA, draft.getId(), Invoice.Status.SENT);
            numbers.add(sent.getInvoiceNumber());
        }

        assertThat(numbers).containsExactly(
                prefix + "0001", prefix + "0002", prefix + "0003");
    }

    /** Mirrors {@code ProjectCodeGeneratorIT.differentTenantsHaveIndependentCounters}. */
    @Test
    void differentTenantsHaveIndependentCounters() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String expected = "INV-" + year + "-0001";

        Invoice a = setStatus(tokenA, createDraft(tokenA).getId(), Invoice.Status.SENT);
        assertThat(a.getInvoiceNumber()).isEqualTo(expected);

        Invoice b = setStatus(tokenB, createDraft(tokenB).getId(), Invoice.Status.SENT);
        assertThat(b.getInvoiceNumber()).isEqualTo(expected);
    }

    /** Mirrors {@code ProjectCodeGeneratorIT.januaryResetSimulation}. */
    @Test
    void januaryResetSimulation() {
        int thisYear = LocalDate.now(ZoneOffset.UTC).getYear();
        int lastYear = thisYear - 1;

        // Seed a "previous year" counter for tenant A at seq=7.
        mongo.insert(
                org.bson.Document.parse(
                        "{\"_id\":\"" + tenantAId + ":" + lastYear + "\",\"seq\":7}"),
                "invoice_number_counters").block();

        Invoice sent = setStatus(tokenA, createDraft(tokenA).getId(), Invoice.Status.SENT);
        // New year ⇒ new year-keyed _id ⇒ starts at 0001 (NOT 0008).
        assertThat(sent.getInvoiceNumber()).isEqualTo("INV-" + thisYear + "-0001");
    }

    /**
     * Finalize-edge idempotency (the §9 discipline): DRAFT→SENT assigns a number
     * exactly once; a re-finalize and a VOIDED→SENT do NOT regenerate it.
     */
    @Test
    void draftToSent_assignsNumberOnce_reFinalizeAndVoidToSentDoNotRegenerate() {
        Invoice draft = createDraft(tokenA);
        assertThat(draft.getInvoiceNumber()).isNull();

        Invoice sent = setStatus(tokenA, draft.getId(), Invoice.Status.SENT);
        String original = sent.getInvoiceNumber();
        assertThat(original).matches("INV-\\d{4}-\\d{4}");

        // Re-finalize (SENT→SENT) — number unchanged.
        Invoice reSent = setStatus(tokenA, draft.getId(), Invoice.Status.SENT);
        assertThat(reSent.getInvoiceNumber()).isEqualTo(original);

        // SENT→VOIDED→SENT — the number must NOT be re-minted (no second burn).
        setStatus(tokenA, draft.getId(), Invoice.Status.VOIDED);
        Invoice reborn = setStatus(tokenA, draft.getId(), Invoice.Status.SENT);
        assertThat(reborn.getInvoiceNumber()).isEqualTo(original);

        // Exactly one counter increment for this tenant/year (seq == 1).
        org.bson.Document counter = mongo.findById(
                        tenantAId + ":" + LocalDate.now(ZoneOffset.UTC).getYear(),
                        org.bson.Document.class, "invoice_number_counters")
                .block();
        assertThat(counter).isNotNull();
        assertThat(((Number) counter.get("seq")).intValue()).isEqualTo(1);
    }

    /**
     * The partial-unique {@code tenant_number_idx} permits N null-numbered DRAFT
     * invoices for one tenant (the resolved blocker) but still rejects a duplicate
     * non-null number for that tenant.
     */
    @Test
    void partialIndex_permitsManyNullDraftsButRejectsDuplicateNumber() {
        // 5 DRAFTs (all invoiceNumber == null) for tenant A — would E11000 under
        // the OLD non-partial unique index; now allowed.
        for (int i = 0; i < 5; i++) {
            assertThat(createDraft(tokenA).getInvoiceNumber()).isNull();
        }
        List<Invoice> drafts = mongo.find(
                        Query.query(org.springframework.data.mongodb.core.query.Criteria
                                .where("tenantId").is(tenantAId)),
                        Invoice.class)
                .collectList().block();
        assertThat(drafts).hasSize(5);
        assertThat(drafts).allMatch(i -> i.getInvoiceNumber() == null);

        // A direct duplicate non-null number for the same tenant must still be
        // rejected by the (partial) unique index.
        Invoice first = drafts.get(0);
        first.setInvoiceNumber("INV-9999-0001");
        mongo.save(first).block();

        Invoice second = drafts.get(1);
        second.setInvoiceNumber("INV-9999-0001");
        StepVerifier.create(mongo.save(second))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    /**
     * AC-E2 (the original blocker, end to end): a monthly recurring template over
     * ≥3 periods for ONE tenant now produces 3 DRAFT invoices in a single tick (no
     * E11000); with {@code autoFinalize=true} each is finalized and gets a
     * distinct, sequential {@code INV-{year}-NNNN}.
     */
    @Test
    void recurringMonthly_threePeriods_autoFinalize_distinctSequentialNumbers() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        java.time.Instant seedAt = java.time.Instant.now()
                .minus(95, java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        RecurringInvoice ri = RecurringInvoice.builder()
                .tenantId(tenantAId)
                .templateName("Monthly autofinalize")
                .rrule("FREQ=MONTHLY")
                .seedAt(seedAt)
                .nextRunAt(seedAt)
                .lastRunAt(seedAt)
                .occurrenceCount(1)
                .status(RecurringInvoice.Status.ACTIVE)
                .autoFinalize(true)
                .paymentTerms(Invoice.PaymentTerms.NET_30)
                .lineItems(List.of(LineItem.builder()
                        .description("Monthly service")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("250.00"))
                        .discountPercent(BigDecimal.ZERO)
                        .taxPercent(BigDecimal.ZERO)
                        .build()))
                .build();
        mongo.save(ri).block();

        // Drive the spawn directly (job disabled). max-catchup default = 12 so all
        // missed monthly periods (seedAt..now, minus the seeded lastRunAt=seedAt →
        // ≥3 due over ~95 days) materialize in ONE tick.
        spawnService.runDueOnce().block();

        List<Invoice> spawned = mongo.find(
                        Query.query(org.springframework.data.mongodb.core.query.Criteria
                                .where("tenantId").is(tenantAId)),
                        Invoice.class)
                .collectList().block();

        // ≥3 invoices, no E11000; each SENT (autoFinalize) with a distinct
        // sequential INV-{year}-NNNN.
        assertThat(spawned).hasSizeGreaterThanOrEqualTo(3);
        assertThat(spawned).allMatch(i -> i.getStatus() == Invoice.Status.SENT);
        List<String> nums = spawned.stream()
                .map(Invoice::getInvoiceNumber)
                .sorted()
                .collect(Collectors.toList());
        assertThat(nums).allMatch(n -> n != null && n.matches("INV-" + year + "-\\d{4}"));
        assertThat(nums).doesNotHaveDuplicates();
        assertThat(nums).hasSameSizeAs(spawned);
    }
}
