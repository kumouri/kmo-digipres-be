package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchAnalytics;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T14 — {@code GET /dispatch/analytics} over a real Mongo: the assigned/unassigned split, the skill-match
 * rate, and the average fit, derived from the same optimizer pass as the proposed plan.
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
class DispatchAnalyticsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private static final LocalDate DAY = LocalDate.of(2026, 7, 3);

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(WorkOrder.class, Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("dispatch-analytics-it-" + tenantId)
                .displayName("DispatchIQ Analytics IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("dispatch", "home-services", "field-service"))
                .build()).block();
        // The dispatcher/admin — an office role, not a field tech (a declared non-field "DISPATCH" skill
        // keeps it out of the field-tech candidate pool).
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@dispatch-analytics.test").displayName("Office Dispatcher")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE)
                .skills(List.of("DISPATCH")).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @Test
    void analytics_reportsAssignedUnassignedAndSkillMatch() {
        // One HVAC tech; three jobs: 2 HVAC (staffable) + 1 POOL (unstaffable, declared-skill mismatch).
        saveTech("dana@dispatch-analytics.test", "Dana", List.of("HVAC"));
        saveWo("Furnace", "HVAC", "EMERGENCY", "LARGE", 8);
        saveWo("AC", "HVAC", "URGENT", "MEDIUM", 10);
        saveWo("Pool heater", "POOL", "ROUTINE", "SMALL", 12);

        DispatchAnalytics a = web.get()
                .uri(uri -> uri.path("/dispatch/analytics").queryParam("date", DAY).build())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DispatchAnalytics.class)
                .returnResult().getResponseBody();

        assertThat(a).isNotNull();
        assertThat(a.totalOpen()).isEqualTo(3);
        assertThat(a.assigned()).isEqualTo(2);     // the two HVAC jobs (one tech can take both)
        assertThat(a.unassigned()).isEqualTo(1);   // the POOL job
        assertThat(a.skillMatched()).isEqualTo(2);
        assertThat(a.skillMatchRate()).isEqualTo(1.0);
        assertThat(a.avgFitScore()).isGreaterThan(0.0);
    }

    @Test
    void analytics_missingDate_is4521() {
        web.get().uri("/dispatch/analytics")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4521);
    }

    // ── helpers ──

    private void saveTech(String email, String name, List<String> skills) {
        users.save(User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email(email).displayName(name)
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE)
                .skills(skills).build()).block();
    }

    private void saveWo(String title, String serviceType, String urgency, String valueBand, int hour) {
        Map<String, Object> cf = new HashMap<>();
        cf.put("urgency", urgency);
        cf.put("jobValueBand", valueBand);
        mongo.save(WorkOrder.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .title(title).serviceType(serviceType)
                .status(WorkOrderStatus.SCHEDULED)
                .scheduledStart(DAY.atTime(hour, 0).toInstant(ZoneOffset.UTC))
                .customFields(cf)
                .build()).block();
    }
}
