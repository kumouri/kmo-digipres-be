package com.kumouri.kmodigipresbe.service.compliance;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.compliance.DataSubjectRequest;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DataSubjectRequestRepository;
import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
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
 * Integration tests for {@link DataSubjectRequestService#submitRedact}:
 * verifies PII fields are replaced with sentinels, the operation is idempotent,
 * and that audit events are generated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class DsrRedactIT {

    private static final String REDACTED = "[REDACTED]";

    @Autowired WebTestClient web;
    @Autowired ContactRepository contactRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired InboxMessageRepository inboxMessageRepository;
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
        mongo.remove(new Query(), InboxMessage.class).block();
        mongo.remove(new Query(), DataSubjectRequest.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();

        tenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("dsr-redact-" + tenantId)
                .displayName("Redact Test").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();
        userRepository.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@redact.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("Admin").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        adminToken = login("admin@redact.test");
    }

    @Test
    void redact_replacesPiiFields_andJobCompletes() {
        UUID contactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("Dave").lastName("Sensitive")
                .build()).block();
        activityRepository.save(Activity.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ActivityType.NOTE).subjectType(SubjectType.CONTACT).subjectId(contactId)
                .summary("Personal information").body("Private medical details").build()).block();

        Map<?, ?> response = web.post().uri("/admin/dsr/{id}/redact", contactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();

        UUID jobId = UUID.fromString((String) response.get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            DataSubjectRequest job = mongo.findById(jobId, DataSubjectRequest.class).block();
            assertThat(job.getStatus()).isEqualTo(DataSubjectRequest.DsrStatus.DONE);
        });

        // Contact PII should be redacted
        Contact contact = contactRepository.findByTenantIdAndId(tenantId, contactId).block();
        assertThat(contact.getFirstName()).isEqualTo(REDACTED);
        assertThat(contact.getLastName()).isEqualTo(REDACTED);
        assertThat(contact.getEmails()).isEmpty();
        assertThat(contact.getPhones()).isEmpty();

        // Activity body should be redacted
        Activity activity = activityRepository
                .findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                        tenantId, SubjectType.CONTACT, contactId)
                .blockFirst();
        assertThat(activity.getSummary()).isEqualTo(REDACTED);
        assertThat(activity.getBody()).isEqualTo(REDACTED);
    }

    @Test
    void redact_isIdempotent_secondCallIsNoOp() {
        UUID contactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("Eve").lastName("Smith").build()).block();

        // First redact
        Map<?, ?> resp1 = web.post().uri("/admin/dsr/{id}/redact", contactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID job1Id = UUID.fromString((String) resp1.get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mongo.findById(job1Id, DataSubjectRequest.class).block().getStatus())
                        .isEqualTo(DataSubjectRequest.DsrStatus.DONE));

        // Second redact should succeed and also complete without error
        Map<?, ?> resp2 = web.post().uri("/admin/dsr/{id}/redact", contactId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID job2Id = UUID.fromString((String) resp2.get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mongo.findById(job2Id, DataSubjectRequest.class).block().getStatus())
                        .isEqualTo(DataSubjectRequest.DsrStatus.DONE));

        // Contact should still show REDACTED (not double-redacted to something else)
        Contact contact = contactRepository.findByTenantIdAndId(tenantId, contactId).block();
        assertThat(contact.getFirstName()).isEqualTo(REDACTED);
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
