package com.kumouri.kmodigipresbe.controller.admin;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.compliance.RetentionPolicy;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.RetentionPolicyRepository;
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
 * Tests for {@link RetentionPolicyController}: GET lists all policies,
 * PUT upserts by entityType.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class RetentionPolicyControllerIT {

    @Autowired WebTestClient web;
    @Autowired RetentionPolicyRepository retentionPolicyRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), RetentionPolicy.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();

        tenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("rp-ctrl-" + tenantId)
                .displayName("RP Controller Test").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();

        userRepository.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@rp.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("Admin").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        adminToken = login("admin@rp.test");
    }

    @Test
    void put_createsPolicy_and_get_returnsIt() {
        web.put().uri("/admin/retention-policies/Activity")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("retentionDays", 90, "exceptions", List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).consumeWith(r -> {
                    Map<?, ?> body = r.getResponseBody();
                    assertThat(body).isNotNull();
                    assertThat(body.get("entityType")).isEqualTo("Activity");
                    assertThat(body.get("retentionDays")).isEqualTo(90);
                });

        List<?> policies = web.get().uri("/admin/retention-policies")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();

        assertThat(policies).hasSize(1);
    }

    @Test
    void put_upserts_existingPolicy() {
        // First PUT
        web.put().uri("/admin/retention-policies/Activity")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("retentionDays", 30, "exceptions", List.of()))
                .exchange()
                .expectStatus().isOk();

        // Second PUT with different value — should update, not insert
        web.put().uri("/admin/retention-policies/Activity")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("retentionDays", 60, "exceptions", List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).consumeWith(r -> {
                    assertThat(r.getResponseBody().get("retentionDays")).isEqualTo(60);
                });

        assertThat(retentionPolicyRepository.findAllByTenantId(tenantId).collectList().block())
                .hasSize(1);
    }

    @Test
    void put_negativeDays_returns400() {
        web.put().uri("/admin/retention-policies/Activity")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("retentionDays", -1, "exceptions", List.of()))
                .exchange()
                .expectStatus().isBadRequest();
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
