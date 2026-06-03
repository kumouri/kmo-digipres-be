package com.kumouri.kmodigipresbe.timetracking;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.TimeEntryRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D4: "Create invoice from time" → DRAFT invoice, source entries marked INVOICED.
 * AC-D5: Idempotency + split-aware aggregation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class InvoiceFromTimeIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ProjectRepository projectRepo;
    @Autowired TimeEntryRepository timeEntryRepo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private UUID userId;
    private String token;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ift-it-" + tenantId)
                .displayName("IFT IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User user = User.builder().id(userId).tenantId(tenantId)
                .email("staff@ift.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void invoiceFromTimeProducesDraftInvoiceAndMarksEntriesInvoiced() {
        // AC-D4: 2 UNBILLED entries (1h + 0.5h at $100/h) → 1 DRAFT invoice, total=$150
        UUID projectId = UUID.randomUUID();
        projectRepo.save(Project.builder().id(projectId).tenantId(tenantId)
                .code("PRJ-2026-I01").name("Invoice Test Project")
                .status(Project.ProjectStatus.ACTIVE).build()).block();

        // (Phase J — J3) Candidate entries must be approved to clear the approved-only
        // invoicing gate; this IT exercises invoice mechanics, not the gate itself.
        Instant start1 = Instant.parse("2026-05-16T09:00:00Z");
        TimeEntry e1 = timeEntryRepo.save(TimeEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId).projectId(projectId)
                .startedAt(start1).endedAt(start1.plusSeconds(3600))
                .durationSeconds(3600L).billable(true).billingStatus(BillingStatus.UNBILLED)
                .approved(true)
                .rateAmount(new BigDecimal("100.00")).description("Design work").build()).block();

        Instant start2 = Instant.parse("2026-05-16T11:00:00Z");
        TimeEntry e2 = timeEntryRepo.save(TimeEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId).projectId(projectId)
                .startedAt(start2).endedAt(start2.plusSeconds(1800))
                .durationSeconds(1800L).billable(true).billingStatus(BillingStatus.UNBILLED)
                .approved(true)
                .rateAmount(new BigDecimal("100.00")).description("Review work").build()).block();

        String requestBody = """
                {"projectId":"%s","defaultRateAmount":100}
                """.formatted(projectId);

        web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.projectId").isEqualTo(projectId.toString())
                .jsonPath("$.total").isEqualTo(150.00);

        // Exactly one invoice
        List<Invoice> invoices = mongo.findAll(Invoice.class).collectList().block();
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getStatus()).isEqualTo(Invoice.Status.DRAFT);

        // Source entries marked INVOICED
        List<TimeEntry> entries = mongo.findAll(TimeEntry.class).collectList().block();
        assertThat(entries).allMatch(e -> e.getBillingStatus() == BillingStatus.INVOICED);
        assertThat(entries).allMatch(e -> e.getInvoicedInvoiceId() != null);

        // INVOICE_FINALIZED was NOT emitted (DRAFT doesn't trigger QBO)
        assertThat(observed).noneMatch(ev -> DomainEventType.INVOICE_FINALIZED.equals(ev.type()));
    }

    @Test
    void invoiceFromTimeIdempotencyNoDoubleInvoice() {
        // AC-D5: re-invoke after all entries are INVOICED → 409 3520
        UUID projectId = UUID.randomUUID();
        projectRepo.save(Project.builder().id(projectId).tenantId(tenantId)
                .code("PRJ-2026-I02").name("Idempotency Test Project")
                .status(Project.ProjectStatus.ACTIVE).build()).block();

        Instant s = Instant.parse("2026-05-16T09:00:00Z");
        timeEntryRepo.save(TimeEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId).projectId(projectId)
                .startedAt(s).endedAt(s.plusSeconds(3600)).durationSeconds(3600L)
                .billable(true).billingStatus(BillingStatus.UNBILLED).approved(true)
                .rateAmount(new BigDecimal("80.00")).build()).block();

        String req = """
                {"projectId":"%s"}
                """.formatted(projectId);

        // First invoke → success
        web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk();

        // Second invoke → 409 3520 (all already invoiced → zero UNBILLED → 3520)
        web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3520);

        // Still exactly one invoice
        assertThat(mongo.findAll(Invoice.class).count().block()).isEqualTo(1L);
    }

    @Test
    void splitGroupAggregatesIntoOneLineItem() {
        // AC-D5: a midnight-split pair (total 8h) → one line item quantity=8, both rows INVOICED
        UUID projectId = UUID.randomUUID();
        projectRepo.save(Project.builder().id(projectId).tenantId(tenantId)
                .code("PRJ-2026-I03").name("Split Aggregation Project")
                .status(Project.ProjectStatus.ACTIVE).build()).block();

        UUID groupId = UUID.randomUUID();
        Instant midnight = Instant.parse("2026-05-19T00:00:00Z");
        // Row A: Mon 20:00 → Tue 00:00 = 4h
        timeEntryRepo.save(TimeEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId).projectId(projectId)
                .startedAt(Instant.parse("2026-05-18T20:00:00Z")).endedAt(midnight)
                .durationSeconds(14400L).billable(true).billingStatus(BillingStatus.UNBILLED)
                .approved(true)
                .rateAmount(new BigDecimal("100.00")).splitGroupId(groupId).build()).block();
        // Row B: Tue 00:00 → Tue 04:00 = 4h
        timeEntryRepo.save(TimeEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId).projectId(projectId)
                .startedAt(midnight).endedAt(Instant.parse("2026-05-19T04:00:00Z"))
                .durationSeconds(14400L).billable(true).billingStatus(BillingStatus.UNBILLED)
                .approved(true)
                .rateAmount(new BigDecimal("100.00")).splitGroupId(groupId).build()).block();

        String req = """
                {"projectId":"%s","defaultRateAmount":100}
                """.formatted(projectId);

        web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.lineItems.length()").isEqualTo(1)
                .jsonPath("$.lineItems[0].quantity").isEqualTo(8.00); // 28800s / 3600 = 8h

        // Both rows marked INVOICED
        List<TimeEntry> entries = mongo.findAll(TimeEntry.class).collectList().block();
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> e.getBillingStatus() == BillingStatus.INVOICED);
    }

    @Test
    void billableEntryWithNoRateAndNoDefaultReturns400With3521() {
        // AC-D5: billable entry has no rate and no default → 400 3521
        UUID projectId = UUID.randomUUID();
        projectRepo.save(Project.builder().id(projectId).tenantId(tenantId)
                .code("PRJ-2026-I04").name("No Rate Project")
                .status(Project.ProjectStatus.ACTIVE).build()).block();

        Instant s = Instant.parse("2026-05-16T09:00:00Z");
        timeEntryRepo.save(TimeEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId).projectId(projectId)
                .startedAt(s).endedAt(s.plusSeconds(3600)).durationSeconds(3600L)
                .billable(true).billingStatus(BillingStatus.UNBILLED).approved(true)
                // No rateAmount — approved so it clears the J3 gate and reaches the rate
                // check → should trigger 3521
                .build()).block();

        String req = """
                {"projectId":"%s"}
                """.formatted(projectId); // no defaultRateAmount

        web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3521);
    }
}
