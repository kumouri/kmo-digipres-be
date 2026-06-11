package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.dispatch.controller.dto.ApplyResponse;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T14 — {@code POST /dispatch/apply} over a real Mongo: applying commits the work orders'
 * {@code technicianUserId} (the board reflects them); a <strong>re-apply of the same decisions is
 * idempotent</strong> (the explicit-boolean already-assigned skip → no double-assign, applied=0); a
 * terminal work order → 4523; an unknown id → 4520; a missing {@code Idempotency-Key} → 3100.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.dispatch.enabled=true",
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.files.region=us-east-1",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class DispatchApplyIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private static final LocalDate DAY = LocalDate.of(2026, 7, 2);

    private UUID tenantId;
    private String staffToken;
    private UUID techId;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(WorkOrder.class, Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("dispatch-apply-it-" + tenantId)
                .displayName("DispatchIQ Apply IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("dispatch", "home-services", "field-service"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@dispatch-apply.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
        User tech = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("dana@dispatch-apply.test").displayName("Dana")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE)
                .skills(List.of("HVAC")).build();
        users.save(tech).block();
        techId = tech.getId();
    }

    @Test
    void apply_commitsAssignment_andReApplyIsIdempotent() {
        UUID woId = saveWo("Furnace", "HVAC", WorkOrderStatus.SCHEDULED, null);

        // First apply → 1 assigned, 0 skipped.
        ApplyResponse first = applyOne(woId, techId);
        assertThat(first.applied()).isEqualTo(1);
        assertThat(first.skipped()).isEqualTo(0);

        // The work order now carries the technician (the board reflects it).
        WorkOrder after = mongo.findById(woId, WorkOrder.class).block();
        assertThat(after).isNotNull();
        assertThat(after.getTechnicianUserId()).isEqualTo(techId);

        // Re-apply the SAME decision → idempotent: 0 applied, 1 skipped (no double-assign).
        ApplyResponse second = applyOne(woId, techId);
        assertThat(second.applied()).isEqualTo(0);
        assertThat(second.skipped()).isEqualTo(1);

        // Still exactly one assignment, unchanged tech.
        WorkOrder reAfter = mongo.findById(woId, WorkOrder.class).block();
        assertThat(reAfter.getTechnicianUserId()).isEqualTo(techId);
    }

    @Test
    void apply_terminalWorkOrder_is4523() {
        UUID woId = saveWo("Closed job", "HVAC", WorkOrderStatus.COMPLETED, null);
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", woId.toString(),
                                "techUserId", techId.toString()))))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4523);
    }

    @Test
    void apply_unknownWorkOrder_is4520() {
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", UUID.randomUUID().toString(),
                                "techUserId", techId.toString()))))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4520);
    }

    @Test
    void apply_unknownTechUserId_is4524() {
        // AI-07: a techUserId that is not a user of this tenant at all → rejected (no write).
        UUID woId = saveWo("Furnace", "HVAC", WorkOrderStatus.SCHEDULED, null);
        UUID bogusTech = UUID.randomUUID();
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", woId.toString(),
                                "techUserId", bogusTech.toString()))))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4524);

        // The work order was NOT assigned (the validation runs before the technicianUserId write).
        WorkOrder after = mongo.findById(woId, WorkOrder.class).block();
        assertThat(after).isNotNull();
        assertThat(after.getTechnicianUserId()).isNull();
    }

    @Test
    void apply_foreignTenantTechUserId_is4524() {
        // AI-07: a real ACTIVE STAFF user, but belonging to a DIFFERENT tenant → rejected (tenant isolation).
        UUID otherTenant = UUID.randomUUID();
        User foreign = User.builder().id(UUID.randomUUID()).tenantId(otherTenant)
                .email("foreign@other-tenant.test").displayName("Foreign")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(foreign).block();

        UUID woId = saveWo("Furnace", "HVAC", WorkOrderStatus.SCHEDULED, null);
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", woId.toString(),
                                "techUserId", foreign.getId().toString()))))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4524);

        WorkOrder after = mongo.findById(woId, WorkOrder.class).block();
        assertThat(after).isNotNull();
        assertThat(after.getTechnicianUserId()).isNull();
    }

    @Test
    void apply_disabledTechUserId_is4524() {
        // AI-07: an in-tenant STAFF user that is DISABLED (not ACTIVE) → rejected.
        User disabled = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("disabled@dispatch-apply.test").displayName("Disabled")
                .roles(Set.of("STAFF")).status(User.UserStatus.DISABLED).build();
        users.save(disabled).block();

        UUID woId = saveWo("Furnace", "HVAC", WorkOrderStatus.SCHEDULED, null);
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", woId.toString(),
                                "techUserId", disabled.getId().toString()))))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4524);
    }

    @Test
    void apply_emptyDecisions_is4522() {
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(), "assignments", List.of()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4522);
    }

    @Test
    void apply_missingIdempotencyKey_is3100() {
        UUID woId = saveWo("Furnace", "HVAC", WorkOrderStatus.SCHEDULED, null);
        web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", woId.toString(),
                                "techUserId", techId.toString()))))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3100);
    }

    // ── helpers ──

    private ApplyResponse applyOne(UUID woId, UUID tech) {
        return web.post().uri("/dispatch/apply")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("date", DAY.toString(),
                        "assignments", List.of(Map.of(
                                "workOrderId", woId.toString(),
                                "techUserId", tech.toString()))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ApplyResponse.class)
                .returnResult().getResponseBody();
    }

    private UUID saveWo(String title, String serviceType, WorkOrderStatus status, UUID assignedTech) {
        UUID id = UUID.randomUUID();
        mongo.save(WorkOrder.builder().id(id).tenantId(tenantId)
                .title(title).serviceType(serviceType)
                .status(status)
                .technicianUserId(assignedTech)
                .scheduledStart(DAY.atTime(9, 0).toInstant(ZoneOffset.UTC))
                .build()).block();
        return id;
    }
}
