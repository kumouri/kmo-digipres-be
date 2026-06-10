package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchPlan;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T14 — {@code GET /dispatch/optimize} over a real Mongo: the optimizer assigns skill-matched techs,
 * prioritizes urgent jobs (the right tech ranks first), and surfaces an unstaffable job UNASSIGNED (not
 * mis-assigned). The {@code CallbackQueueAndStatsIT} WebTestClient + JWT pattern; field-service +
 * home-services on so the WorkOrder spine is present; {@code dispatch} on + in the tenant's enabledModules.
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
class DispatchOptimizeIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private static final LocalDate DAY = LocalDate.of(2026, 7, 1);

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(WorkOrder.class, JobSite.class, Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("dispatch-opt-it-" + tenantId)
                .displayName("DispatchIQ Optimize IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("dispatch", "home-services", "field-service"))
                .build()).block();
        // The dispatcher/admin account — an office role, NOT a field tech. A declared non-field skill
        // ("DISPATCH") keeps it out of the field-tech candidate pool (declared-mismatch → ineligible),
        // so the optimizer's candidate set is exactly the three field techs seeded per test.
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@dispatch-opt.test").displayName("Office Dispatcher")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE)
                .skills(List.of("DISPATCH")).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @Test
    void optimize_assignsSkillMatchedTechs_prioritizesUrgent_andSurfacesUnstaffable() {
        // Techs: Dana (HVAC+ELECTRICAL), Marco (HVAC), Priya (PLUMBING only).
        UUID dana = saveTech("dana@dispatch-opt.test", "Dana", List.of("HVAC", "ELECTRICAL"));
        UUID marco = saveTech("marco@dispatch-opt.test", "Marco", List.of("HVAC"));
        saveTech("priya@dispatch-opt.test", "Priya", List.of("PLUMBING"));

        // Jobs: EMERGENCY furnace (HVAC), ROUTINE tune-up (HVAC), URGENT panel (ELECTRICAL),
        // and a POOL job (a service type NO tech has → unstaffable).
        UUID emergencyId = saveWo("Furnace no-heat", "HVAC", "EMERGENCY", "LARGE", 8);
        saveWo("Seasonal tune-up", "HVAC", "ROUTINE", "SMALL", 13);
        UUID panelId = saveWo("Panel upgrade", "ELECTRICAL", "URGENT", "LARGE", 11);
        UUID poolId = saveWo("Pool heater", "POOL", "ROUTINE", "MEDIUM", 9);

        DispatchPlan plan = web.get()
                .uri(uri -> uri.path("/dispatch/optimize").queryParam("date", DAY).build())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DispatchPlan.class)
                .returnResult().getResponseBody();

        assertThat(plan).isNotNull();
        assertThat(plan.openCount()).isEqualTo(4);

        // The EMERGENCY job heads the priority-first assignment list and is on an HVAC-skilled tech.
        assertThat(plan.assignments()).isNotEmpty();
        assertThat(plan.assignments().get(0).workOrderId()).isEqualTo(emergencyId);
        assertThat(plan.assignments().get(0).urgency()).isEqualTo("EMERGENCY");
        assertThat(plan.assignments().get(0).skillMatched()).isTrue();
        assertThat(plan.assignments().get(0).assignedTechUserId()).isIn(dana, marco);

        // The ELECTRICAL panel can only go to Dana (the only ELECTRICAL-skilled tech).
        var panel = plan.assignments().stream()
                .filter(a -> a.workOrderId().equals(panelId)).findFirst().orElseThrow();
        assertThat(panel.assignedTechUserId()).isEqualTo(dana);
        assertThat(panel.skillMatched()).isTrue();

        // The POOL job is unstaffable (no tech has a matching declared skill, and all techs DO have
        // declared skills) → surfaced unassigned with a reason, never mis-assigned.
        assertThat(plan.assignments()).noneMatch(a -> a.workOrderId().equals(poolId));
        var pool = plan.unassigned().stream()
                .filter(a -> a.workOrderId().equals(poolId)).findFirst().orElseThrow();
        assertThat(pool.assignedTechUserId()).isNull();
        assertThat(pool.unassignedReason()).isNotBlank();

        // skill-match rate is 1.0 (every assignment went to a matching tech).
        assertThat(plan.skillMatchRate()).isEqualTo(1.0);
        assertThat(plan.avgFitScore()).isGreaterThan(0.0);
    }

    @Test
    void optimize_missingDate_is4521() {
        web.get().uri("/dispatch/optimize")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4521);
    }

    // ── helpers ──

    private UUID saveTech(String email, String name, List<String> skills) {
        User u = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email(email).displayName(name)
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE)
                .skills(skills).build();
        users.save(u).block();
        return u.getId();
    }

    private UUID saveWo(String title, String serviceType, String urgency, String valueBand, int hour) {
        UUID jsId = UUID.randomUUID();
        mongo.save(JobSite.builder().id(jsId).tenantId(tenantId)
                .label(title + " site").location(LatLng.of(38.62, -90.20)).build()).block();
        Map<String, Object> cf = new HashMap<>();
        cf.put("urgency", urgency);
        cf.put("jobValueBand", valueBand);
        UUID id = UUID.randomUUID();
        mongo.save(WorkOrder.builder().id(id).tenantId(tenantId)
                .title(title).serviceType(serviceType)
                .status(WorkOrderStatus.SCHEDULED)
                .jobSiteId(jsId)
                .scheduledStart(at(hour))
                .customFields(cf)
                .build()).block();
        return id;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }
}
