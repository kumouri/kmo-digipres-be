package com.kumouri.kmodigipresbe.timetracking;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.Expense.ApprovalStatus;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
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
import org.springframework.data.mongodb.core.query.Criteria;
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
 * AC-D6 (receipt portion): Expense receipt uses the existing /attachments presign/register flow
 * with subjectType="EXPENSE" — zero new file-storage code (D-D12).
 *
 * <p>This test directly registers a fake attachment (skipping the S3 presign step, which
 * requires a live MinIO/S3 instance) and verifies that:
 * <ul>
 *   <li>GET /attachments?subjectType=EXPENSE&subjectId={id} lists the attachment.</li>
 *   <li>The existing Attachment entity's free-form subjectType field accepts "EXPENSE".</li>
 * </ul>
 * The S3 presign step itself is covered by the existing AttachmentController + S3 ITs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class ExpenseReceiptAttachmentIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ExpenseRepository expenseRepo;
    @Autowired AttachmentRepository attachmentRepo;

    private UUID tenantId;
    private UUID userId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("receipt-it-" + tenantId)
                .displayName("Receipt IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User user = User.builder().id(userId).tenantId(tenantId)
                .email("staff@receipt.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Expense.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void attachmentRegisteredWithExpenseSubjectTypeIsListedViaExistingAttachmentEndpoint() {
        // Seed an expense
        UUID expenseId = UUID.randomUUID();
        expenseRepo.save(Expense.builder()
                .id(expenseId).tenantId(tenantId).userId(userId)
                .description("Conference fee").amount(new BigDecimal("500.00"))
                .incurredOn(LocalDate.of(2026, 5, 16))
                .approvalStatus(ApprovalStatus.PENDING).billingStatus(BillingStatus.UNBILLED)
                .billable(true).build()).block();

        // Register an attachment with subjectType="EXPENSE" using the existing /attachments POST
        // (simulating the FE flow: presign → S3 PUT → register)
        // We use a fake storageRef that passes the tenant-prefix validation
        String storageRef = "tenants/" + tenantId + "/attachments/EXPENSE/" + expenseId + "/receipt.pdf";
        String attachmentBody = """
                {
                  "subjectType": "EXPENSE",
                  "subjectId": "%s",
                  "storageRef": "%s",
                  "filename": "receipt.pdf",
                  "contentType": "application/pdf",
                  "sizeBytes": 12345
                }
                """.formatted(expenseId, storageRef);

        web.post().uri("/attachments")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(attachmentBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.subjectType").isEqualTo("EXPENSE")
                .jsonPath("$.subjectId").isEqualTo(expenseId.toString());

        // List attachments via the existing GET /attachments?subjectType=EXPENSE&subjectId={id}
        web.get().uri("/attachments?subjectType=EXPENSE&subjectId=" + expenseId)
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Attachment.class)
                .hasSize(1)
                .value(list -> {
                    assertThat(list.get(0).getSubjectType()).isEqualTo("EXPENSE");
                    assertThat(list.get(0).getSubjectId()).isEqualTo(expenseId);
                });

        // Verify the Attachment entity accepts EXPENSE as a free-form subjectType
        List<Attachment> attachments = mongo.findAll(Attachment.class).collectList().block();
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).getSubjectType()).isEqualTo("EXPENSE");
    }
}
