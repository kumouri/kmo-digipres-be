package com.kumouri.kmodigipresbe.project;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.project.MilestoneRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-C4: Milestone triggersInvoiceOnComplete=true → DRAFT invoice spawned via InvoiceService.create.
 * AC-C5: Idempotency — completing an already-COMPLETED milestone doesn't spawn a second invoice.
 * AC-C6: autoFinalizeMilestoneInvoices=true → spawned invoice is SENT + INVOICE_FINALIZED emitted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class MilestoneInvoiceSpawnIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ProjectRepository projectRepo;
    @Autowired MilestoneRepository milestoneRepo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private String token;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Milestone.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ms-it-" + tenantId)
                .displayName("MS IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@ms.test").roles(Set.of("STAFF", "ADMIN"))
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

    @Test
    void completingTriggeringMilestoneSpawnsDraftInvoice() {
        // Seed: project + milestone with triggersInvoiceOnComplete=true and line items
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .code("PRJ-2026-T01")
                .name("Spawn Test Project")
                .status(Project.ProjectStatus.ACTIVE)
                .build()).block();

        LineItem line = LineItem.builder()
                .description("Consulting")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("100.00"))
                .taxPercent(BigDecimal.ZERO)
                .discountPercent(BigDecimal.ZERO)
                .build();

        Milestone milestone = milestoneRepo.save(Milestone.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(project.getId())
                .name("Billing Milestone")
                .status(Milestone.MilestoneStatus.IN_PROGRESS)
                .triggersInvoiceOnComplete(true)
                .invoiceLineItems(List.of(line))
                .build()).block();

        // Transition → COMPLETED
        web.post().uri("/milestones/" + milestone.getId() + "/transition?status=COMPLETED")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.completedAt").isNotEmpty()
                .jsonPath("$.spawnedInvoiceId").isNotEmpty();

        // Assert exactly one invoice for this milestone
        List<Invoice> invoices = mongo.findAll(Invoice.class).collectList().block();
        assertThat(invoices).isNotNull();
        List<Invoice> forMilestone = invoices.stream()
                .filter(i -> milestone.getId().equals(i.getMilestoneId()))
                .toList();
        assertThat(forMilestone).hasSize(1);

        Invoice spawned = forMilestone.get(0);
        assertThat(spawned.getStatus()).isEqualTo(Invoice.Status.DRAFT);
        assertThat(spawned.getProjectId()).isEqualTo(project.getId());
        assertThat(spawned.getMilestoneId()).isEqualTo(milestone.getId());
        assertThat(spawned.getTotal()).isEqualByComparingTo("200.00");

        // Assert INVOICE_FINALIZED was NOT emitted (draft must not trigger QBO)
        assertThat(observed).noneMatch(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type()));
    }

    @Test
    void idempotencyNoDoubleSpawn() {
        // AC-C5: re-complete an already-COMPLETED milestone → still exactly one invoice
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-T02")
                .name("Idempotency Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        LineItem line = LineItem.builder().description("Work").quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("50.00")).discountPercent(BigDecimal.ZERO)
                .taxPercent(BigDecimal.ZERO).build();

        Milestone milestone = milestoneRepo.save(Milestone.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(project.getId())
                .name("Idempotent Milestone").status(Milestone.MilestoneStatus.IN_PROGRESS)
                .triggersInvoiceOnComplete(true).invoiceLineItems(List.of(line)).build()).block();

        // First complete
        web.post().uri("/milestones/" + milestone.getId() + "/transition?status=COMPLETED")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isOk();

        // Second complete (should be idempotent — no second invoice)
        web.post().uri("/milestones/" + milestone.getId() + "/transition?status=COMPLETED")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isOk();

        long invoiceCount = mongo.findAll(Invoice.class)
                .filter(i -> milestone.getId().equals(i.getMilestoneId()))
                .count().block();
        assertThat(invoiceCount).isEqualTo(1);
    }

    @Test
    void nonTriggeringMilestoneDoesNotSpawnInvoice() {
        // AC-C5: triggersInvoiceOnComplete=false → zero invoices spawned
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-T03")
                .name("No Invoice Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        Milestone milestone = milestoneRepo.save(Milestone.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(project.getId())
                .name("Non-triggering").status(Milestone.MilestoneStatus.PENDING)
                .triggersInvoiceOnComplete(false).build()).block();

        web.post().uri("/milestones/" + milestone.getId() + "/transition?status=COMPLETED")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isOk();

        long invoiceCount = mongo.findAll(Invoice.class)
                .filter(i -> milestone.getId().equals(i.getMilestoneId()))
                .count().block();
        assertThat(invoiceCount).isEqualTo(0);
    }

    @Test
    void autoFinalizeSendsInvoiceAndEmitsInvoiceFinalized() {
        // AC-C6: Project.autoFinalizeMilestoneInvoices=true → SENT invoice + INVOICE_FINALIZED event
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-T04")
                .name("Auto Finalize Project").status(Project.ProjectStatus.ACTIVE)
                .autoFinalizeMilestoneInvoices(true).build()).block();

        LineItem line = LineItem.builder().description("Finalize me").quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("300.00")).discountPercent(BigDecimal.ZERO)
                .taxPercent(BigDecimal.ZERO).build();

        Milestone milestone = milestoneRepo.save(Milestone.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(project.getId())
                .name("Auto Finalize Milestone").status(Milestone.MilestoneStatus.PENDING)
                .triggersInvoiceOnComplete(true).invoiceLineItems(List.of(line)).build()).block();

        web.post().uri("/milestones/" + milestone.getId() + "/transition?status=COMPLETED")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isOk();

        // Invoice should be SENT (auto-finalized)
        List<Invoice> forMilestone = mongo.findAll(Invoice.class)
                .filter(i -> milestone.getId().equals(i.getMilestoneId()))
                .collectList().block();
        assertThat(forMilestone).hasSize(1);
        assertThat(forMilestone.get(0).getStatus()).isEqualTo(Invoice.Status.SENT);

        // INVOICE_FINALIZED event should have been emitted
        Awaitility.await().atMost(3, TimeUnit.SECONDS)
                .until(() -> observed.stream()
                        .anyMatch(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type())));
    }
}
