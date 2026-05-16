package com.kumouri.kmodigipresbe.auth;

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
 * AC-5 (local half): with no auth props set (kmosf.auth.mode defaults to "local"):
 * - Local HS256 token is accepted on /contacts
 * - /auth/discovery returns mode=local
 * - App boots without any Zitadel config (A/A2 boundary)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
        // No kmosf.auth.mode set — defaults to "local"
        // No kmosf.auth.zitadel.* set — app boots without Zitadel config
})
class AuthModeLocalIT {

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

    private String localToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        UUID tid = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tid).slug("auth-local-" + tid)
                .displayName("Auth Local Test")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .email("local@example.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("LocalUser")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        localToken = login("local@example.test", "pass1234");
    }

    @Test
    void localTokenIsAcceptedOnProtectedEndpoint() {
        // AC-5: local HS256 token works when mode=local (default)
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + localToken)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void discoveryEndpointReturnsModeLocal() {
        // AC-5: /auth/discovery returns mode=local when no auth mode is configured
        web.get().uri("/auth/discovery")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result -> {
                    Map<?, ?> body = result.getResponseBody();
                    assertThat(body).isNotNull();
                    assertThat(body.get("mode")).isEqualTo("local");
                    assertThat(body.get("loginPath")).isEqualTo("/auth/login");
                });
    }

    @Test
    void discoveryEndpointIsAccessibleWithoutAuth() {
        // /auth/discovery is permitted unauthenticated
        web.get().uri("/auth/discovery")
                .exchange()
                .expectStatus().isOk();
    }

    private String login(String email, String password) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
