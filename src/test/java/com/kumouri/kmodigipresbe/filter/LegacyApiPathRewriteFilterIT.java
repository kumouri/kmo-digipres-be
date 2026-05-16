package com.kumouri.kmodigipresbe.filter;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-3: Legacy /api/contacts resolves with Deprecation header.
 *
 * Uses @LocalServerPort + absolute URI to bypass WebTestClient base-path, so we can
 * directly send /api/* requests (which the filter should rewrite to /api/v1/*).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class LegacyApiPathRewriteFilterIT {

    @LocalServerPort
    int port;

    @Autowired
    WebTestClient web;

    @Autowired
    TenantRepository tenants;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ReactiveMongoTemplate mongo;

    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        UUID tid = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tid).slug("rewrite-" + tid)
                .displayName("Rewrite Test")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .email("rewrite@example.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("RewriteUser")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        token = login("rewrite@example.test", "pass1234");
    }

    @Test
    void legacyApiPathIsRewrittenWithDeprecationHeader() {
        // AC-3: /api/contacts (legacy, no /v1/) should be rewritten to /api/v1/contacts.
        // Use absolute URI with port to bypass WebTestClient's base-path.
        WebTestClient raw = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        raw.get()
                .uri("/api/contacts")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("Deprecation")
                .expectHeader().valueEquals("Deprecation", "true")
                .expectHeader().exists("Sunset")
                .expectHeader().exists("Link");
    }

    @Test
    void alreadyVersionedPathIsNotDoubleRewritten() {
        // Loop guard: /api/v1/contacts must NOT be rewritten again (no double-prefix).
        // The filter's !startsWith("/api/v1/") exclusion prevents /api/v1/v1/contacts.
        WebTestClient raw = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // /api/v1/contacts should pass through and NOT have a Deprecation header
        raw.get()
                .uri("/api/v1/contacts")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Deprecation");
    }

    private String login(String email, String password) {
        // Login via the versioned path (which WebTestClient resolves via base-path)
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
