package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 (Health "RescheduleFlow") — HTTP tests for the {@link RescheduleController} admin surface. Drives the
 * endpoints over {@code WebTestClient} (the {@code NoShowRiskControllerIT} JWT-auth pattern), seeding rows
 * directly via Mongo. A pure read/admin surface — no WireMock, no Twilio.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@code POST /frontdesk/reschedule/waitlist} (ADMIN) joins the health waitlist → a generic
 *       {@link WaitlistEntry} with {@code slotType="health-appt"} (logistics only); list returns it;</li>
 *   <li>an empty body (no contactId) → 4421;</li>
 *   <li>{@code GET /frontdesk/reschedule/fill-stats} returns zeroed stats for a fresh tenant;</li>
 *   <li>both-module gate: a tenant missing the {@code waitlist} module → 1132;</li>
 *   <li>a non-ADMIN role → 1800.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.waitlist.enabled=true"
})
class RescheduleControllerIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void seed() {
        wipe();
        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk", "waitlist"));
        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@reschedule.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);
    }

    @AfterEach
    void cleanup() {
        wipe();
    }

    private void wipe() {
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // ── 1. join the health waitlist → list returns it ────────────────────────────

    @Test
    void joinWaitlist_createsHealthApptEntry_listReturnsIt() {
        UUID contactId = UUID.randomUUID();
        WaitlistEntry created = web.post().uri("/frontdesk/reschedule/waitlist")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("contactId", contactId.toString(),
                        "notes", "any afternoon works"))
                .exchange().expectStatus().isCreated()
                .expectBody(WaitlistEntry.class).returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getContactId()).isEqualTo(contactId);
        assertThat(created.getSlotType()).isEqualTo(FrontDeskSlotMaterializer.SLOT_TYPE);
        assertThat(created.getStatus()).isEqualTo(WaitlistEntry.Status.OPEN);
        assertThat(created.isSmsOptIn()).isTrue();

        List<WaitlistEntry> list = web.get().uri("/frontdesk/reschedule/waitlist")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(WaitlistEntry.class).returnResult().getResponseBody();
        assertThat(list).extracting(WaitlistEntry::getId).contains(created.getId());
    }

    // ── 2. empty body → 4421 ─────────────────────────────────────────────────────

    @Test
    void joinWaitlist_noContactId_is4421() {
        web.post().uri("/frontdesk/reschedule/waitlist")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("notes", "no contact"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4421);
    }

    // ── 3. fill-stats zeroed for a fresh tenant ──────────────────────────────────

    @Test
    void fillStats_zeroedForFreshTenant() {
        web.get().uri("/frontdesk/reschedule/fill-stats")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cancellations").isEqualTo(0)
                .jsonPath("$.offers").isEqualTo(0)
                .jsonPath("$.claims").isEqualTo(0)
                .jsonPath("$.filled").isEqualTo(0)
                .jsonPath("$.fillRate").isEqualTo(0.0);
    }

    // ── 4. both-module gate: missing waitlist module → 1132 ──────────────────────

    @Test
    void missingWaitlistModule_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of("frontdesk")); // drop waitlist
        tenants.save(t).block();

        web.get().uri("/frontdesk/reschedule/fill-stats")
                .header("Authorization", adminToken)
                .exchange()
                // 1132 (module not enabled for tenant) is a 404 (the requireEnabled / NoShowRiskControllerIT
                // convention), NOT a 403.
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── 5. non-ADMIN role → 1800 ─────────────────────────────────────────────────

    @Test
    void nonAdminRole_isForbidden_1800() {
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@reschedule.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        String staffToken = "Bearer " + jwt.mint(staff);

        web.get().uri("/frontdesk/reschedule/fill-stats")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("reschedule-ctl-it-" + tid)
                .displayName("RescheduleFlow Controller IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }
}
