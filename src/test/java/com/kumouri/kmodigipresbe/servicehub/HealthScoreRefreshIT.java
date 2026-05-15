package com.kumouri.kmodigipresbe.servicehub;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
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

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class HealthScoreRefreshIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ContactRepository contacts;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID contactId;
    private String adminToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("hs-refresh-" + tenantId)
                .displayName("HS Refresh").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@hs-refresh.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Admin").roles(Set.of("ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        contactId = UUID.randomUUID();
        contacts.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .displayName("Refresh Test Contact").build()).block();

        adminToken = login("admin@hs-refresh.test");
    }

    @Test
    void manualRefresh_triggersComputeAndReturnsCount() {
        Map<?, ?> result = web.post().uri("/admin/health-score/refresh")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(((Number) result.get("scored")).longValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void contactHealthScoreEndpoint_returnsScore() {
        // Trigger compute first so the contact gets a score persisted
        web.post().uri("/admin/health-score/refresh")
                .header("Authorization", "Bearer " + adminToken)
                .exchange().expectStatus().isOk();

        web.get().uri("/contacts/" + contactId + "/health-score")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.score").isNotEmpty()
                .jsonPath("$.tier").isNotEmpty();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
