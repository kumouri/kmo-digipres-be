package com.kumouri.kmodigipresbe.timetracking;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.audit.AuditOp;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.Expense.ApprovalStatus;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.ExpenseRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D8: All new entities are Auditable — CREATE and UPDATE audit events are emitted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class TimeExpenseAuditIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired TimeEntryRepository timeEntryRepo;
    @Autowired ExpenseRepository expenseRepo;

    private UUID tenantId;
    private UUID userId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), AuditEvent.class).block();
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("audit-it-" + tenantId)
                .displayName("Audit IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User user = User.builder().id(userId).tenantId(tenantId)
                .email("staff@audit.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), AuditEvent.class).block();
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void createTimeEntryProducesAuditCreateEvent() {
        Instant start = Instant.parse("2026-05-16T09:00:00Z");
        String body = """
                {"startedAt":"%s","endedAt":"%s","description":"Audit test"}
                """.formatted(start, start.plusSeconds(3600));

        web.post().uri("/time-entries")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            List<AuditEvent> audits = mongo.findAll(AuditEvent.class).collectList().block();
            return audits != null && audits.stream()
                    .anyMatch(a -> "TimeEntry".equals(a.getEntityType()) && AuditOp.CREATE == a.getOp());
        });
    }

    @Test
    void createExpenseProducesAuditCreateEvent() {
        String body = """
                {"description":"Conference","amount":250.00,"incurredOn":"2026-05-16"}
                """;

        web.post().uri("/expenses")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            List<AuditEvent> audits = mongo.findAll(AuditEvent.class).collectList().block();
            return audits != null && audits.stream()
                    .anyMatch(a -> "Expense".equals(a.getEntityType()) && AuditOp.CREATE == a.getOp());
        });
    }

    @Test
    void approveExpenseProducesAuditUpdateEvent() {
        Expense e = expenseRepo.save(Expense.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .description("Meal").amount(new BigDecimal("50.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.PENDING).billingStatus(BillingStatus.UNBILLED)
                .billable(true).build()).block();

        // Clear the CREATE audit to make the UPDATE assertion cleaner
        mongo.remove(new Query(), AuditEvent.class).block();

        web.post().uri("/expenses/" + e.getId() + "/approve")
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            List<AuditEvent> audits = mongo.findAll(AuditEvent.class).collectList().block();
            return audits != null && audits.stream()
                    .anyMatch(a -> "Expense".equals(a.getEntityType()) && AuditOp.UPDATE == a.getOp());
        });
    }

    @Test
    void stopTimerProducesAuditUpdateEvent() {
        Instant start = Instant.parse("2026-05-18T09:00:00Z");
        String startBody = """
                {"userId":"%s","startedAt":"%s"}
                """.formatted(userId, start);

        web.post().uri("/time-entries/timer/start")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(startBody)
                .exchange()
                .expectStatus().isCreated();

        // Clear CREATE audits
        mongo.remove(new Query(), AuditEvent.class).block();

        web.post().uri("/time-entries/timer/stop?userId=" + userId
                + "&endedAt=2026-05-18T11:00:00Z&zoneId=UTC")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            List<AuditEvent> audits = mongo.findAll(AuditEvent.class).collectList().block();
            return audits != null && audits.stream()
                    .anyMatch(a -> "TimeEntry".equals(a.getEntityType()) && AuditOp.UPDATE == a.getOp());
        });
    }
}
