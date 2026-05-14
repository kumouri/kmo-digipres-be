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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class AuthSmokeIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    @BeforeEach
    void seed() {
        // Reuse a single Mongo container across all *IT classes, so wipe the
        // collections we touch before each test to avoid colliding on the
        // unique email index when the next test re-inserts smoke@example.test.
        // Go via ReactiveMongoTemplate, not the tenant-scoped repositories —
        // User is TenantScoped, so users.deleteAll() would demand a tenant
        // context the test hasn't established yet.
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), User.class).block();
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), Tenant.class).block();

        UUID tid = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tid).slug("smoke-" + tid).displayName("Smoke")
                .status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID())
                .tenantId(tid)
                .email("smoke@example.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Smoke")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build()).block();
    }

    @Test
    void unauthenticatedContactsListReturns401() {
        web.get().uri("/contacts").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void loginIssuesJwtAndProtectedEndpointAccepts() {
        Map<?, ?> loginBody = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", "smoke@example.test", "password", "hunter2hunter2"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        String token = (String) loginBody.get("token");

        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        web.get().uri("/auth/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void wrongPasswordReturns401() {
        web.post().uri("/auth/login")
                .bodyValue(Map.of("email", "smoke@example.test", "password", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
