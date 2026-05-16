package com.kumouri.kmodigipresbe.timetracking;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D6 (invoice portion): Expense + receipt → DRAFT invoice with markup.
 * - APPROVED billable UNBILLED expense amount=100, markupPercent=15 → unitPrice=115.00, total=115.00
 * - PENDING expense → 409 3530
 * - Re-invoke after invoiced → 409 3530
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class InvoiceFromExpensesMarkupIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ExpenseRepository expenseRepo;

    private UUID tenantId;
    private UUID userId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ife-it-" + tenantId)
                .displayName("IFE IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User user = User.builder().id(userId).tenantId(tenantId)
                .email("staff@ife.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void approvedExpenseWithMarkupProducesDraftInvoiceWithCorrectLineItemPrice() {
        // AC-D6: amount=100, markupPercent=15 → unitPrice = 100 * 1.15 = 115.00, total=115.00
        UUID expenseId = UUID.randomUUID();
        expenseRepo.save(Expense.builder()
                .id(expenseId).tenantId(tenantId).userId(userId)
                .description("Travel to client site").amount(new BigDecimal("100.00"))
                .markupPercent(new BigDecimal("15"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.APPROVED) // approved
                .billingStatus(BillingStatus.UNBILLED).billable(true).build()).block();

        String req = """
                {"expenseIds":["%s"]}
                """.formatted(expenseId);

        web.post().uri("/expenses/invoice-from-expenses")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.lineItems.length()").isEqualTo(1)
                .jsonPath("$.lineItems[0].unitPrice").isEqualTo(115.00)
                .jsonPath("$.total").isEqualTo(115.00);

        // Expense marked INVOICED
        List<Expense> expenses = mongo.findAll(Expense.class).collectList().block();
        assertThat(expenses).hasSize(1);
        assertThat(expenses.get(0).getBillingStatus()).isEqualTo(BillingStatus.INVOICED);
        assertThat(expenses.get(0).getInvoicedInvoiceId()).isNotNull();
    }

    @Test
    void pendingExpenseCannotBeInvoiced409With3530() {
        // AC-D6: PENDING (unapproved) expense → 409 3530
        UUID expenseId = UUID.randomUUID();
        expenseRepo.save(Expense.builder()
                .id(expenseId).tenantId(tenantId).userId(userId)
                .description("Conference fee").amount(new BigDecimal("500.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.PENDING) // NOT approved
                .billingStatus(BillingStatus.UNBILLED).billable(true).build()).block();

        String req = """
                {"expenseIds":["%s"]}
                """.formatted(expenseId);

        web.post().uri("/expenses/invoice-from-expenses")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3530);
    }

    @Test
    void reinvokeAfterAllInvoicedReturns409() {
        // AC-D6 idempotency: re-invoke after invoiced → 409
        UUID expenseId = UUID.randomUUID();
        expenseRepo.save(Expense.builder()
                .id(expenseId).tenantId(tenantId).userId(userId)
                .description("Software subscription").amount(new BigDecimal("200.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.APPROVED)
                .billingStatus(BillingStatus.UNBILLED).billable(true).build()).block();

        String req = """
                {"expenseIds":["%s"]}
                """.formatted(expenseId);

        // First invoke → success
        web.post().uri("/expenses/invoice-from-expenses")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk();

        // Second invoke → 409 (3530 or 3532)
        web.post().uri("/expenses/invoice-from-expenses")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(409);

        // Still exactly one invoice
        assertThat(mongo.findAll(Invoice.class).count().block()).isEqualTo(1L);
    }
}
