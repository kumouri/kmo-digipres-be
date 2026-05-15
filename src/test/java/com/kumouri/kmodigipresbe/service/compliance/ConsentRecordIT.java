package com.kumouri.kmodigipresbe.service.compliance;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.compliance.ConsentRecord;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.ConsentRecordRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the GDPR consent record lifecycle:
 * record → withdraw → double-withdraw.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class ConsentRecordIT {

    @Autowired WebTestClient web;
    @Autowired ConsentRecordRepository consentRecordRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID contactId;
    private String staffToken;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), ConsentRecord.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();

        tenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("consent-" + tenantId)
                .displayName("Consent Test").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();

        userRepository.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@consent.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("Staff").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        contactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("Alice").lastName("Consent").build()).block();

        staffToken = login("staff@consent.test");
    }

    @Test
    void recordAndWithdraw_cycle_succeeds() {
        // Record consent
        Map<?, ?> created = web.post().uri("/contacts/{id}/consents", contactId)
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of(
                        "topic", "marketing-email",
                        "lawfulBasis", "CONSENT",
                        "source", "staff-entry"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(created).isNotNull();
        String consentId = (String) created.get("id");
        assertThat(created.get("status")).isEqualTo("GRANTED");

        // Verify GET lists the record
        List<?> all = web.get().uri("/contacts/{id}/consents", contactId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();
        assertThat(all).hasSize(1);

        // Withdraw
        web.delete().uri("/contacts/{id}/consents/{cid}", contactId, consentId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isNoContent();

        // Verify status is WITHDRAWN
        ConsentRecord after = consentRecordRepository
                .findByTenantIdAndId(tenantId, UUID.fromString(consentId)).block();
        assertThat(after).isNotNull();
        assertThat(after.getStatus()).isEqualTo(ConsentRecord.ConsentStatus.WITHDRAWN);
        assertThat(after.getWithdrawnAt()).isNotNull();
    }

    @Test
    void doubleWithdraw_returns409() {
        // Record and withdraw once
        Map<?, ?> created = web.post().uri("/contacts/{id}/consents", contactId)
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of(
                        "topic", "sms-notifications",
                        "lawfulBasis", "CONSENT",
                        "source", "staff-entry"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        String consentId = (String) created.get("id");

        web.delete().uri("/contacts/{id}/consents/{cid}", contactId, consentId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isNoContent();

        // Second withdraw should 409
        web.delete().uri("/contacts/{id}/consents/{cid}", contactId, consentId)
                .header("Authorization", "Bearer " + staffToken)
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
