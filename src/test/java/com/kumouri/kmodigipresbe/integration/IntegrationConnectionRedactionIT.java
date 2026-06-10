package com.kumouri.kmodigipresbe.integration;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Security fix BE-03 — integration secrets are never exposed and the routes are ADMIN-gated.
 * <ul>
 *   <li>An ADMIN {@code GET /integrations/connections} returns the redacted view:
 *       NO {@code secrets}/{@code config} keys, but {@code hasSecrets:true}.</li>
 *   <li>A STAFF-only token is 403 (the {@link com.kumouri.kmodigipresbe.tenancy.StaffAuthorizationWebFilter}
 *       ADMIN gate on {@code /integrations/connections/**}).</li>
 *   <li>A write via the {@code ConnectionWrite} DTO round-trips the redacted view and never
 *       echoes the secret values; tenantId/id/version are not bindable.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class IntegrationConnectionRedactionIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;

    private static final String SECRET_VALUE = "sk_live_SUPER_SECRET_DO_NOT_LEAK";

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("intsec-" + tenantId)
                .displayName("Int Sec IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        User admin = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@intsec.test").roles(Set.of("STAFF", "ADMIN"))
                .portal(User.Portal.STAFF).status(User.UserStatus.ACTIVE).build()).block();
        adminToken = jwt.mint(admin);

        User staff = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@intsec.test").roles(Set.of("STAFF"))
                .portal(User.Portal.STAFF).status(User.UserStatus.ACTIVE).build()).block();
        staffToken = jwt.mint(staff);

        // Pre-set tenantId + save via mongo (bypasses the stamping callback, mirrors the
        // other IT seed pattern for TenantScoped docs).
        mongo.save(IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider("stripe")
                .displayName("Stripe")
                .status(IntegrationConnection.Status.ACTIVE)
                .secrets(Map.of("apiKey", SECRET_VALUE,
                        "webhookSigningSecret", "whsec_alsosecret"))
                .config(Map.of("apiBaseUrl", "https://api.stripe.com"))
                .build()).block();
    }

    // ─── ADMIN read returns the redacted view (no secrets) ───────────────────────

    @Test
    void adminList_redactsSecretsAndConfig() {
        web.get().uri("/integrations/connections")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].provider").isEqualTo("stripe")
                .jsonPath("$[0].displayName").isEqualTo("Stripe")
                .jsonPath("$[0].status").isEqualTo("ACTIVE")
                .jsonPath("$[0].hasSecrets").isEqualTo(true)
                // The raw maps must NOT be serialized at all.
                .jsonPath("$[0].secrets").doesNotExist()
                .jsonPath("$[0].config").doesNotExist();
    }

    @Test
    void adminList_responseBodyContainsNoSecretValue() {
        byte[] body = web.get().uri("/integrations/connections")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String json = body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(json).doesNotContain(SECRET_VALUE);
        org.assertj.core.api.Assertions.assertThat(json).doesNotContain("whsec_alsosecret");
        org.assertj.core.api.Assertions.assertThat(json).doesNotContain("\"secrets\"");
    }

    @Test
    void adminGetByProvider_redactsSecrets() {
        web.get().uri("/integrations/connections/by-provider/stripe")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.hasSecrets").isEqualTo(true)
                .jsonPath("$.secrets").doesNotExist();
    }

    // ─── STAFF-only token is forbidden (ADMIN gate) ──────────────────────────────

    @Test
    void staffToken_forbidden() {
        web.get().uri("/integrations/connections")
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1804);
    }

    // ─── Write DTO round-trips redacted; secret never echoed ──────────────────────

    @Test
    void adminUpsert_returnsRedactedView_noSecretEcho() {
        byte[] body = web.post().uri("/integrations/connections")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of(
                        "provider", "twilio",
                        "displayName", "Twilio",
                        "secrets", Map.of("authToken", SECRET_VALUE),
                        // attempt mass-assignment of protected fields — must be ignored
                        "tenantId", UUID.randomUUID().toString(),
                        "id", UUID.randomUUID().toString(),
                        "version", 99))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("twilio")
                .jsonPath("$.hasSecrets").isEqualTo(true)
                .jsonPath("$.secrets").doesNotExist()
                .returnResult().getResponseBody();
        String json = body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(json).doesNotContain(SECRET_VALUE);
    }
}
