package com.kumouri.kmodigipresbe.sync;

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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class SyncAllowlistIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        UUID tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("allowlist-it-" + tenantId)
                .displayName("Allowlist IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@allowlist.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@allowlist.test");
    }

    @Test
    void pullOnNonAllowlistedCollectionReturns400WithCode1500() {
        web.get()
                .uri("/sync/invoices?since=1970-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1500);
    }

    @Test
    void pushOnNonAllowlistedCollectionReturns400WithCode1500() {
        web.post()
                .uri("/sync/invoices")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "fields", Map.of("amount", "999"),
                        "clientUpdatedAt", "1970-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1500);
    }

    @Test
    void unauthenticatedPullReturns401() {
        web.get()
                .uri("/sync/contacts?since=1970-01-01T00:00:00Z")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
