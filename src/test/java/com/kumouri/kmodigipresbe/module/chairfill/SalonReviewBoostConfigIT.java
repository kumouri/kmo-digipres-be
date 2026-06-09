package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.reviewboost.ReviewBoostConfigDTO;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6 Salon "ReviewBoost" — SalonReviewBoostConfigIT: the ReviewBoost wiring read-back
 * ({@code GET /api/v1/chairfill/reviewboost/config}). ADMIN JWT; pure read over the per-tenant Twilio
 * {@link IntegrationConnection} ({@code config["reviewLink"]}) + the effective default-OFF E3 flags. No
 * WireMock, no external (§7).
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>a tenant with a {@code reviewLink} on its Twilio connection → {@code reviewLinkConfigured=true} +
 *       the link echoed; the three default-OFF flags are reported {@code false} (the engine defaults);</li>
 *   <li>a tenant with a Twilio connection but no {@code reviewLink} → {@code reviewLinkConfigured=false},
 *       {@code reviewLink=null};</li>
 *   <li>a tenant with no Twilio connection at all → {@code reviewLinkConfigured=false} (no NPE — the
 *       {@code mapNotNull} + {@code switchIfEmpty} guard);</li>
 *   <li>non-ADMIN → 1800.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true"
})
class SalonReviewBoostConfigIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void config_reviewLinkConfigured_andFlagsDefaultOff() {
        UUID tenantId = seedTenant();
        String admin = adminToken(tenantId);
        saveTwilioConnection(tenantId, "https://example.com/glow/review");

        ReviewBoostConfigDTO cfg = web.get().uri("/chairfill/reviewboost/config")
                .header("Authorization", admin)
                .exchange().expectStatus().isOk()
                .expectBody(ReviewBoostConfigDTO.class).returnResult().getResponseBody();

        assertThat(cfg).isNotNull();
        assertThat(cfg.reviewLinkConfigured()).isTrue();
        assertThat(cfg.reviewLink()).isEqualTo("https://example.com/glow/review");
        // The E3 sends are all default-OFF.
        assertThat(cfg.senderEnabled()).isFalse();
        assertThat(cfg.sentimentRefineEnabled()).isFalse();
        assertThat(cfg.negativeAlertEnabled()).isFalse();
    }

    @Test
    void config_connectionButNoReviewLink_notConfigured() {
        UUID tenantId = seedTenant();
        String admin = adminToken(tenantId);
        saveTwilioConnection(tenantId, null);

        ReviewBoostConfigDTO cfg = web.get().uri("/chairfill/reviewboost/config")
                .header("Authorization", admin)
                .exchange().expectStatus().isOk()
                .expectBody(ReviewBoostConfigDTO.class).returnResult().getResponseBody();

        assertThat(cfg).isNotNull();
        assertThat(cfg.reviewLinkConfigured()).isFalse();
        assertThat(cfg.reviewLink()).isNull();
    }

    @Test
    void config_noTwilioConnection_notConfigured_noNpe() {
        UUID tenantId = seedTenant();
        String admin = adminToken(tenantId);
        // No connection saved.

        ReviewBoostConfigDTO cfg = web.get().uri("/chairfill/reviewboost/config")
                .header("Authorization", admin)
                .exchange().expectStatus().isOk()
                .expectBody(ReviewBoostConfigDTO.class).returnResult().getResponseBody();

        assertThat(cfg).isNotNull();
        assertThat(cfg.reviewLinkConfigured()).isFalse();
        assertThat(cfg.reviewLink()).isNull();
    }

    @Test
    void nonAdmin_isForbidden_1800() {
        UUID tenantId = seedTenant();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@rbc.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        String staffToken = "Bearer " + jwt.mint(staff);

        web.get().uri("/chairfill/reviewboost/config")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    // ── helpers ──

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("rbc-it-" + tenantId)
                .displayName("ReviewBoost Config IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill"))
                .aiBudgetUsd(new BigDecimal("5.00")).build()).block();
        return tenantId;
    }

    private String adminToken(UUID tenantId) {
        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@rbc.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        return "Bearer " + jwt.mint(admin);
    }

    private void saveTwilioConnection(UUID tenantId, String reviewLink) {
        Map<String, String> config = new HashMap<>();
        config.put("notifyPhone", "+12145550400");
        if (reviewLink != null) {
            config.put("reviewLink", reviewLink);
        }
        IntegrationConnection conn = IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "sandbox")))
                .config(config)
                .build();
        mongo.save(conn).contextWrite(TenantContextHolder.write(
                new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN")))).block();
    }
}
