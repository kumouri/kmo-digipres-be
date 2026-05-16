package com.kumouri.kmodigipresbe.timetracking;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.Expense.ApprovalStatus;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.ExpenseRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D7: Expense approval workflow on existing RBAC.
 * - ADMIN approve → 200, approvalStatus=APPROVED, approvedByUserId/decidedAt set
 * - ADMIN reject without reason → 400 3516; with reason → REJECTED
 * - STAFF (non-ADMIN) approve → 403 1800
 * - INVOICED expense approve → 409 3517
 * - Illegal transition APPROVED→PENDING → 409 3515
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class ExpenseApprovalIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ExpenseRepository expenseRepo;

    private UUID tenantId;
    private UUID userId;
    private String adminToken;
    private String staffToken; // STAFF-only (no ADMIN)

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ea-it-" + tenantId)
                .displayName("EA IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User adminUser = User.builder().id(userId).tenantId(tenantId)
                .email("admin@ea.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(adminUser).block();
        adminToken = "Bearer " + jwt.mint(adminUser);

        User staffUser = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@ea.test").roles(Set.of("STAFF")) // no ADMIN
                .status(User.UserStatus.ACTIVE).build();
        users.save(staffUser).block();
        staffToken = "Bearer " + jwt.mint(staffUser);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private Expense seedPendingExpense() {
        return expenseRepo.save(Expense.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .description("Hotel stay").amount(new BigDecimal("250.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.PENDING).billingStatus(BillingStatus.UNBILLED)
                .billable(true).build()).block();
    }

    @Test
    void adminApprovesExpense() {
        Expense e = seedPendingExpense();

        web.post().uri("/expenses/" + e.getId() + "/approve")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.approvalStatus").isEqualTo("APPROVED")
                .jsonPath("$.approvedByUserId").isNotEmpty()
                .jsonPath("$.decidedAt").isNotEmpty();

        Expense saved = mongo.findAll(Expense.class).next().block();
        assertThat(saved.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(saved.getApprovedByUserId()).isNotNull();
        assertThat(saved.getDecidedAt()).isNotNull();
    }

    @Test
    void rejectWithoutReasonReturns400With3516() {
        Expense e = seedPendingExpense();

        web.post().uri("/expenses/" + e.getId() + "/reject?reason=")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3516);
    }

    @Test
    void adminRejectsExpenseWithReason() {
        Expense e = seedPendingExpense();

        web.post().uri("/expenses/" + e.getId() + "/reject?reason=Not+eligible")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.approvalStatus").isEqualTo("REJECTED")
                .jsonPath("$.rejectionReason").isEqualTo("Not eligible");

        Expense saved = mongo.findAll(Expense.class).next().block();
        assertThat(saved.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(saved.getRejectionReason()).isEqualTo("Not eligible");
    }

    @Test
    void nonAdminApproveReturns403With1800() {
        Expense e = seedPendingExpense();

        web.post().uri("/expenses/" + e.getId() + "/approve")
                .header("Authorization", staffToken) // STAFF only — no ADMIN
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void invoicedExpenseApproveReturns409With3517() {
        Expense e = expenseRepo.save(Expense.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .description("Software license").amount(new BigDecimal("100.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.APPROVED)
                .billingStatus(BillingStatus.INVOICED) // already invoiced
                .invoicedInvoiceId(UUID.randomUUID()).billable(true).build()).block();

        web.post().uri("/expenses/" + e.getId() + "/approve")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3517);
    }

    @Test
    void illegalTransitionApprovedToPendingReturns409With3515() {
        // APPROVED→PENDING is in ILLEGAL_TRANSITIONS
        Expense e = expenseRepo.save(Expense.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .description("Meal").amount(new BigDecimal("50.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.APPROVED) // already approved
                .billingStatus(BillingStatus.UNBILLED).billable(true).build()).block();

        // Try to approve again (APPROVED→APPROVED is in ILLEGAL_TRANSITIONS)
        web.post().uri("/expenses/" + e.getId() + "/approve")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3515);
    }
}
