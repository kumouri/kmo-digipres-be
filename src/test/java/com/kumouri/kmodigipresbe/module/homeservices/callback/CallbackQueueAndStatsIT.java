package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.callback.dto.CallbackCardDTO;
import com.kumouri.kmodigipresbe.module.homeservices.callback.dto.CallbackRecoveryStats;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — the dispatcher HTTP surface ({@code CallbackController}): the revenue-ranked queue read, dispatch
 * transition, recovery-stats, config CRUD, and the module gate. The {@code ArAgingIT} WebTestClient + JWT
 * pattern. The both-modules gate ({@code home-services} + {@code responder}) is satisfied by the tenant's
 * {@code enabledModules}; {@code responder} is default-on so no flag is needed beyond {@code home-services}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class CallbackQueueAndStatsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(CallbackRequest.class, CallbackFunnelLog.class, CallbackConfig.class,
                Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("callback-queue-it-" + tenantId)
                .displayName("Callback Queue IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("home-services", "responder"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@callback-queue.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    // ── 1. The revenue-ranked queue orders high-value first ──────────────────────────────────

    @Test
    void queue_ordersHighValueFirst() {
        // SMALL/ROUTINE, MEDIUM/URGENT, LARGE/EMERGENCY — saved out of order.
        saveCard("small", "SMALL", "ROUTINE", CallbackStatus.REQUESTED);
        saveCard("large", "LARGE", "EMERGENCY", CallbackStatus.REQUESTED);
        saveCard("medium", "MEDIUM", "URGENT", CallbackStatus.REQUESTED);
        // A DISPATCHED card must NOT appear in the open queue.
        saveCard("done", "LARGE", "EMERGENCY", CallbackStatus.DISPATCHED);

        List<CallbackCardDTO> queue = web.get().uri("/home-services/callbacks")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(CallbackCardDTO.class)
                .returnResult().getResponseBody();

        assertThat(queue).isNotNull();
        assertThat(queue).extracting(CallbackCardDTO::fromPhone)
                .containsExactly("large", "medium", "small");   // ranked, DISPATCHED excluded
    }

    // ── 2. dispatch: REQUESTED → DISPATCHED, then a second dispatch → 4402 ───────────────────

    @Test
    void dispatch_transitionsThenDoubleDispatchIs4402() {
        CallbackRequest card = saveCard("+13145550001", "LARGE", "EMERGENCY", CallbackStatus.REQUESTED);

        CallbackCardDTO dispatched = web.post()
                .uri("/home-services/callbacks/{id}/dispatch", card.getId())
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody(CallbackCardDTO.class)
                .returnResult().getResponseBody();
        assertThat(dispatched).isNotNull();
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        assertThat(funnelCount(CallbackFunnelStage.DISPATCHED)).isEqualTo(1);

        // A second dispatch on the now-DISPATCHED card → 4402 (explicit-boolean guard).
        web.post().uri("/home-services/callbacks/{id}/dispatch", card.getId())
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4402);
    }

    @Test
    void dispatch_unknownId_is4400() {
        web.post().uri("/home-services/callbacks/{id}/dispatch", UUID.randomUUID())
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4400);
    }

    // ── 3. recovery-stats reports the funnel + rates ─────────────────────────────────────────

    @Test
    void recoveryStats_reportsFunnelAndRates() {
        // 3 offered, 2 accepted, 1 dispatched.
        saveFunnel(CallbackFunnelStage.OFFERED, 3);
        saveFunnel(CallbackFunnelStage.ACCEPTED, 2);
        saveFunnel(CallbackFunnelStage.DISPATCHED, 1);

        CallbackRecoveryStats stats = web.get().uri("/home-services/callbacks/recovery-stats")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(CallbackRecoveryStats.class)
                .returnResult().getResponseBody();

        assertThat(stats).isNotNull();
        assertThat(stats.offered()).isEqualTo(3);
        assertThat(stats.accepted()).isEqualTo(2);
        assertThat(stats.dispatched()).isEqualTo(1);
        assertThat(stats.acceptanceRate()).isEqualTo(2.0 / 3.0);
        assertThat(stats.dispatchRate()).isEqualTo(1.0 / 2.0);
    }

    // ── 4. config CRUD ───────────────────────────────────────────────────────────────────────

    @Test
    void config_getBeforeUpsertIs4401_thenUpsertRoundTrips() {
        web.get().uri("/home-services/callbacks/config")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4401);

        web.put().uri("/home-services/callbacks/config")
                .header("Authorization", staffToken)
                .bodyValue(new CallbackController.ConfigRequest(
                        "Missed you! Reply NOW or a time.", "We'll call right back.", "We'll call then."))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.offerMessage").isEqualTo("Missed you! Reply NOW or a time.");

        web.get().uri("/home-services/callbacks/config")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.immediateConfirmMessage").isEqualTo("We'll call right back.");
    }

    // ── 5. module gate: a tenant without both modules → 1132 ─────────────────────────────────

    @Test
    void queue_tenantWithoutResponderModule_is1132() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("callback-nomod-" + otherTenantId)
                .displayName("No-responder Tenant").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("home-services"))   // responder NOT enabled for the tenant
                .build()).block();
        User otherStaff = User.builder().id(UUID.randomUUID()).tenantId(otherTenantId)
                .email("staff@callback-nomod.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(otherStaff).block();
        String otherToken = "Bearer " + jwt.mint(otherStaff);

        web.get().uri("/home-services/callbacks")
                .header("Authorization", otherToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── helpers ──

    private long funnelCount(CallbackFunnelStage stage) {
        return mongo.findAll(CallbackFunnelLog.class).collectList().block().stream()
                .filter(l -> l.getStage() == stage).count();
    }

    private CallbackRequest saveCard(String phone, String band, String urgency, CallbackStatus status) {
        return mongo.save(CallbackRequest.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .fromPhone(phone)
                .mode(CallbackMode.IMMEDIATE)
                .status(status)
                .urgency(urgency)
                .jobValueBand(band)
                .revenueScore(CallbackRevenueRanker.score(band, urgency, Instant.now()))
                .build()).block();
    }

    private void saveFunnel(CallbackFunnelStage stage, int n) {
        for (int i = 0; i < n; i++) {
            mongo.save(CallbackFunnelLog.builder()
                    .id(UUID.randomUUID()).tenantId(tenantId)
                    .stage(stage).callSid("CA-" + stage + "-" + i)
                    .occurredAt(Instant.now())
                    .build()).block();
        }
    }
}
