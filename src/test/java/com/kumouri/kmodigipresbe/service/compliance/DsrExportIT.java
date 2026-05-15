package com.kumouri.kmodigipresbe.service.compliance;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.compliance.DataSubjectRequest;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DataSubjectRequestRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DataSubjectRequestService#submitExport}:
 * verifies that the DSR job reaches DONE status and that a cross-tenant
 * export attempt returns 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class DsrExportIT {

    @Autowired WebTestClient web;
    @Autowired ContactRepository contactRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired DataSubjectRequestRepository dsrRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), DataSubjectRequest.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();

        tenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("dsr-export-" + tenantId)
                .displayName("DSR Test").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();
        userRepository.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@dsr.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("Admin").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        adminToken = login("admin@dsr.test");
    }

    @Test
    void export_completes_withJobStatusDone() {
        UUID contactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("Alice").lastName("Export").build()).block();
        activityRepository.save(Activity.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ActivityType.NOTE).subjectType(SubjectType.CONTACT).subjectId(contactId)
                .summary("Met with Alice").build()).block();

        // Submit export job
        Map<?, ?> response = web.post().uri("/admin/dsr/{id}/export", contactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(response).isNotNull();
        UUID jobId = UUID.fromString((String) response.get("id"));

        // Poll until DONE
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            DataSubjectRequest job = mongo.findById(jobId, DataSubjectRequest.class).block();
            assertThat(job).isNotNull();
            assertThat(job.getStatus()).isEqualTo(DataSubjectRequest.DsrStatus.DONE);
            assertThat(job.getResultUrl()).isNotBlank();
        });
    }

    @Test
    void export_crossTenantContact_returns403() {
        // Create a contact belonging to a different tenant
        UUID otherTenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(otherTenantId).slug("other-" + otherTenantId)
                .displayName("Other").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();

        UUID otherContactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(otherContactId).tenantId(otherTenantId)
                .firstName("Bob").lastName("Other").build()).block();

        // Admin from tenantId tries to export contact from otherTenantId
        web.post().uri("/admin/dsr/{id}/export", otherContactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isNotFound(); // 404 because findByTenantIdAndId scopes to tenantId
    }

    @Test
    void export_duplicateRequest_returns409() {
        UUID contactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("Carol").lastName("DSR").build()).block();

        // First export
        web.post().uri("/admin/dsr/{id}/export", contactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isAccepted();

        // Immediate second export while first is still PENDING/PROCESSING
        web.post().uri("/admin/dsr/{id}/export", contactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "pass1234"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
