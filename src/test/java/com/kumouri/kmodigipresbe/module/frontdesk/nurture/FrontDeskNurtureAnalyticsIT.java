package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSendLog;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService.NurtureCampaignAnalytics;
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

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 — the frontdesk-scoped per-segment reactivation analytics read ({@code GET
 * /frontdesk/nurture/campaigns/{id}/analytics}, reusing {@code NurtureAnalyticsService}) + the both-modules
 * gate. The health twin of {@code RealEstateNurtureAnalyticsIT}. Proves: an ADMIN read returns the
 * per-campaign + per-bucket funnel counts; a tenant missing the {@code nurture} module membership → rejected
 * (the both-module {@code requireEnabled}); a non-ADMIN → 1800.
 *
 * <p>The frontdesk module is enabled (so the controller + {@code FrontDeskNurtureService} bean exist); the
 * tenant carries {@code enabledModules=["frontdesk","nurture"]} for the happy path. Self-clean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true"
})
class FrontDeskNurtureAnalyticsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;
    private UUID campaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureSendLog.class).block();
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("fd-nurture-analytics-it-" + tenantId)
                .displayName("Bright Smiles Analytics IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "nurture"))
                .build()).block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@bright-smiles-analytics.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@bright-smiles-analytics.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        NurtureCampaign campaign = mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).name("Health Reactivation").active(true)
                .build()).block();
        campaignId = campaign.getId();

        // Seed enrollments across buckets/statuses to exercise the per-segment funnel.
        seedEnrollment(DormancyBucket.A, NurtureEnrollmentStatus.BOOKED);
        seedEnrollment(DormancyBucket.A, NurtureEnrollmentStatus.REPLIED);
        seedEnrollment(DormancyBucket.B, NurtureEnrollmentStatus.ACTIVE);
        seedEnrollment(DormancyBucket.B, NurtureEnrollmentStatus.ENROLLED);
        seedEnrollment(DormancyBucket.C, NurtureEnrollmentStatus.COMPLETED);
    }

    private void seedEnrollment(DormancyBucket bucket, NurtureEnrollmentStatus status) {
        mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(UUID.randomUUID())
                .bucket(bucket).status(status)
                .enrolledAt(java.time.Instant.now())
                .build()).block();
    }

    @Test
    void admin_getsPerSegmentFunnel() {
        NurtureCampaignAnalytics analytics = web.get()
                .uri("/frontdesk/nurture/campaigns/" + campaignId + "/analytics")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(NurtureCampaignAnalytics.class)
                .returnResult().getResponseBody();

        assertThat(analytics).isNotNull();
        assertThat(analytics.total()).isEqualTo(5);
        assertThat(analytics.booked()).isEqualTo(1);
        assertThat(analytics.replied()).isEqualTo(1);
        // Per-bucket breakdown: A has 2 (1 booked + 1 replied), B has 2, C has 1.
        assertThat(analytics.perBucket().get(DormancyBucket.A).total()).isEqualTo(2);
        assertThat(analytics.perBucket().get(DormancyBucket.A).booked()).isEqualTo(1);
        assertThat(analytics.perBucket().get(DormancyBucket.B).total()).isEqualTo(2);
        assertThat(analytics.perBucket().get(DormancyBucket.C).total()).isEqualTo(1);
    }

    @Test
    void nonAdmin_isForbidden_1800() {
        web.get().uri("/frontdesk/nurture/campaigns/" + campaignId + "/analytics")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void tenantMissingNurtureModule_isRejected() {
        // A tenant with frontdesk but NOT nurture in enabledModules → the both-module requireEnabled fails.
        UUID otherTenant = UUID.randomUUID();
        tenants.save(Tenant.builder().id(otherTenant).slug("fd-only-it-" + otherTenant)
                .displayName("FD only").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk"))
                .build()).block();
        User admin = User.builder().id(UUID.randomUUID()).tenantId(otherTenant)
                .email("admin@fd-only.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        String token = "Bearer " + jwt.mint(admin);

        UUID someCampaign = UUID.randomUUID();
        web.get().uri("/frontdesk/nurture/campaigns/" + someCampaign + "/analytics")
                .header("Authorization", token)
                .exchange()
                // requireEnabled("nurture") fails for this tenant → a 4xx module-gate error (not 200/2xx).
                .expectStatus().value(s -> assertThat(s).isGreaterThanOrEqualTo(400));
    }
}
