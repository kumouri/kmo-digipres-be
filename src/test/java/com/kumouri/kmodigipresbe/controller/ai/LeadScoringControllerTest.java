package com.kumouri.kmodigipresbe.controller.ai;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
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
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Integration tests for {@link LeadScoringController}:
 * GET /contacts/{id}/lead-score returns 404 when the contact has no score, and 200 with
 * the score once one has been set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class LeadScoringControllerTest {

    @Autowired WebTestClient web;
    @Autowired ContactRepository contactRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();

        tenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("lsc-" + tenantId).displayName("Lead Scoring Test")
                .status(Tenant.TenantStatus.ACTIVE).aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
        userRepository.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@lsc.test")
                .passwordHash(encoder.encode("password123"))
                .displayName("Staff")
                .roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE)
                .build()).block();

        staffToken = login("staff@lsc.test");
    }

    @Test
    void getLeadScore_returnsNotFound_whenContactHasNoScore() {
        UUID contactId = UUID.randomUUID();
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("No").lastName("Score")
                .build()).block();

        web.get().uri("/contacts/{id}/lead-score", contactId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getLeadScore_returnsScore_whenContactHasBeenScored() {
        UUID contactId = UUID.randomUUID();
        LeadScore score = new LeadScore(0.82, LeadScore.TIER_HOT, LeadScore.SOURCE_RULES_FALLBACK,
                Instant.parse("2026-05-14T02:00:00Z"));
        contactRepository.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .firstName("Hot").lastName("Lead")
                .leadScore(score)
                .build()).block();

        web.get().uri("/contacts/{id}/lead-score", contactId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tier").isEqualTo("HOT")
                .jsonPath("$.source").isEqualTo("RULES_FALLBACK")
                .jsonPath("$.score").isEqualTo(0.82);
    }

    @Test
    void getLeadScore_returnsNotFound_whenContactDoesNotExist() {
        web.get().uri("/contacts/{id}/lead-score", UUID.randomUUID())
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "password123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
