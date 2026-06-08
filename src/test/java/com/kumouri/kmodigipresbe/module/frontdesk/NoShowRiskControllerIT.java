package com.kumouri.kmodigipresbe.module.frontdesk;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.FrontDeskScoringJob;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrontDesk IQ (FD-1) — HTTP tests for the no-show risk read + retrain controller and the appointment CRUD.
 * Drives the endpoints over {@code WebTestClient} (the {@code RealEstateConciergeConversationReadIT}
 * JWT-auth pattern), seeding rows directly via Mongo. A pure read/control surface — no WireMock, no Twilio.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@code POST /frontdesk/risk/retrain} returns 202 + a {@link FrontDeskScoringJob};</li>
 *   <li>{@code GET /frontdesk/risk/appointments?from&to} returns the window's appointments risk-sorted
 *       (highest first; unscored last);</li>
 *   <li>appointment CRUD: create → list → get round-trips; an invalid create payload → 4277;</li>
 *   <li>{@code GET} a missing appointment → 4276;</li>
 *   <li>a non-frontdesk tenant → 1132 module-gate not-enabled;</li>
 *   <li>a non-staff role → 1800 forbidden on the CRUD.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true"
})
class NoShowRiskControllerIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wipe();
        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@frontdesk.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @AfterEach
    void cleanup() {
        wipe();
    }

    private void wipe() {
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), FrontDeskScoringJob.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // ── 1. retrain returns 202 + a job ────────────────────────────────────────────

    @Test
    void retrain_returns202AndJob() {
        FrontDeskScoringJob job = web.post().uri("/frontdesk/risk/retrain")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(FrontDeskScoringJob.class).returnResult().getResponseBody();

        assertThat(job).isNotNull();
        assertThat(job.getId()).isNotNull();
        assertThat(job.getTenantId()).isEqualTo(tenantId);
        assertThat(job.getStatus()).isIn(
                FrontDeskScoringJob.JobStatus.PENDING,
                FrontDeskScoringJob.JobStatus.RUNNING,
                FrontDeskScoringJob.JobStatus.DONE);
    }

    // ── 2. risk-sorted day view ───────────────────────────────────────────────────

    @Test
    void upcomingByRisk_returnsRiskSorted() {
        Instant from = Instant.now();
        Instant to = Instant.now().plus(Duration.ofDays(7));

        UUID low = seedScored(Instant.now().plus(Duration.ofDays(1)), 0.10, NoShowRisk.TIER_LOW);
        UUID high = seedScored(Instant.now().plus(Duration.ofDays(2)), 0.85, NoShowRisk.TIER_HIGH);
        UUID med = seedScored(Instant.now().plus(Duration.ofDays(3)), 0.45, NoShowRisk.TIER_MEDIUM);

        List<Appointment> rows = web.get()
                .uri(uri -> uri.path("/frontdesk/risk/appointments")
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .build())
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(Appointment.class).returnResult().getResponseBody();

        assertThat(rows).isNotNull();
        assertThat(rows).extracting(Appointment::getId).containsExactly(high, med, low);
    }

    // ── 3. CRUD round-trip + 4277 on invalid create ───────────────────────────────

    @Test
    void crud_createListGet_roundTrips() {
        Appointment body = Appointment.builder()
                .contactId(UUID.randomUUID())
                .providerId(UUID.randomUUID())
                .scheduledStart(Instant.now().plus(Duration.ofDays(2)))
                .visitTypeBucket(VisitTypeBucket.RECALL)
                .insuranceVerificationPending(true)
                .build();

        Appointment created = web.post().uri("/frontdesk/appointments")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(Appointment.class).returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getVisitTypeBucket()).isEqualTo(VisitTypeBucket.RECALL);

        Appointment fetched = web.get().uri("/frontdesk/appointments/" + created.getId())
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(Appointment.class).returnResult().getResponseBody();
        assertThat(fetched.getId()).isEqualTo(created.getId());

        List<Appointment> list = web.get().uri("/frontdesk/appointments")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(Appointment.class).returnResult().getResponseBody();
        assertThat(list).extracting(Appointment::getId).contains(created.getId());
    }

    @Test
    void create_invalidPayload_is4277() {
        // Missing contactId + scheduledStart -> nothing to score -> 4277.
        Appointment body = Appointment.builder()
                .visitTypeBucket(VisitTypeBucket.NEW_PATIENT)
                .build();

        web.post().uri("/frontdesk/appointments")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4277);
    }

    // ── 4. missing appointment -> 4276 ────────────────────────────────────────────

    @Test
    void get_missingAppointment_is4276() {
        web.get().uri("/frontdesk/appointments/" + UUID.randomUUID())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4276);
    }

    // ── 5. non-frontdesk tenant -> 1132 module gate ───────────────────────────────

    @Test
    void nonFrontdeskTenant_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of()); // drop frontdesk
        tenants.save(t).block();

        web.get().uri("/frontdesk/appointments")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── 6. non-staff role -> 1800 forbidden ───────────────────────────────────────

    @Test
    void nonStaff_crud_isForbidden_1800() {
        User noRole = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("norole@frontdesk.test")
                .roles(Set.of()).status(User.UserStatus.ACTIVE).build();
        users.save(noRole).block();
        String noRoleToken = "Bearer " + jwt.mint(noRole);

        web.get().uri("/frontdesk/appointments")
                .header("Authorization", noRoleToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("frontdesk-it-" + tid)
                .displayName("FrontDesk IQ IT Practice").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    private UUID seedScored(Instant start, double score, String tier) {
        UUID id = UUID.randomUUID();
        mongo.save(Appointment.builder()
                .id(id).tenantId(tenantId)
                .contactId(UUID.randomUUID())
                .providerId(UUID.randomUUID())
                .scheduledStart(start)
                .scheduledEnd(start.plus(Duration.ofMinutes(30)))
                .status(AppointmentStatus.SCHEDULED)
                .visitTypeBucket(VisitTypeBucket.RECALL)
                .noShowRisk(new NoShowRisk(score, tier, NoShowRisk.SOURCE_RULES_FALLBACK, Instant.now()))
                .build()).block();
        return id;
    }
}
