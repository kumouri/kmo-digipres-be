package com.kumouri.kmodigipresbe.contractor;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contractor.ProjectAssignment;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.contractor.ProjectAssignmentRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — bill + cost rates are resolved and stamped on a {@link TimeEntry} at log time,
 * with the order: explicit body value → {@link ProjectAssignment} override → {@link User}
 * default → null. Also asserts the entry is parented to a Timesheet period and is not
 * pre-approved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class RateStampingIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAssignmentRepository assignments;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID userU;   // token user — defaults 100 / 50
    private UUID userV;   // no defaults
    private UUID projectP; // userU assigned with overrides 120 / 60
    private String token;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("rs-it-" + tenantId)
                .displayName("RS IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userU = UUID.randomUUID();
        User u = User.builder().id(userU).tenantId(tenantId).email("u@rs.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE)
                .defaultBillRate(new BigDecimal("100")).defaultCostRate(new BigDecimal("50"))
                .build();
        users.save(u).block();
        token = "Bearer " + jwt.mint(u);

        userV = UUID.randomUUID();
        users.save(User.builder().id(userV).tenantId(tenantId).email("v@rs.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build()).block();

        projectP = UUID.randomUUID();
        projects.save(Project.builder().id(projectP).tenantId(tenantId).code("PRJ-RS-001")
                .name("RS Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        assignments.save(ProjectAssignment.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .projectId(projectP).userId(userU)
                .billRateOverride(new BigDecimal("120")).costRateOverride(new BigDecimal("60"))
                .active(true).build()).block();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Timesheet.class).block();
        mongo.remove(new Query(), ProjectAssignment.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private TimeEntry postEntry(String json) {
        web.post().uri("/time-entries")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isCreated();
        List<TimeEntry> rows = mongo.findAll(TimeEntry.class).collectList().block();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void assignedProjectStampsAssignmentOverrideRates() {
        TimeEntry e = postEntry("""
                {"startedAt":"2026-05-18T09:00:00Z","endedAt":"2026-05-18T11:00:00Z","projectId":"%s"}
                """.formatted(projectP));
        assertThat(e.getRateAmount()).isEqualByComparingTo("120");
        assertThat(e.getCostRateAmount()).isEqualByComparingTo("60");
        assertThat(e.getTimesheetId()).isNotNull();
        assertThat(e.isApproved()).isFalse();
    }

    @Test
    void noProjectFallsBackToUserDefaultRates() {
        TimeEntry e = postEntry("""
                {"startedAt":"2026-05-18T09:00:00Z","endedAt":"2026-05-18T10:00:00Z"}
                """);
        assertThat(e.getRateAmount()).isEqualByComparingTo("100");
        assertThat(e.getCostRateAmount()).isEqualByComparingTo("50");
    }

    @Test
    void noAssignmentNoDefaultsLeavesRatesNull() {
        // logged on behalf of userV (no defaults), no project → both rates null
        TimeEntry e = postEntry("""
                {"startedAt":"2026-05-18T09:00:00Z","endedAt":"2026-05-18T10:00:00Z","userId":"%s"}
                """.formatted(userV));
        assertThat(e.getRateAmount()).isNull();
        assertThat(e.getCostRateAmount()).isNull();
    }

    @Test
    void explicitBodyBillRateWinsWhileCostResolvesFromAssignment() {
        TimeEntry e = postEntry("""
                {"startedAt":"2026-05-18T09:00:00Z","endedAt":"2026-05-18T10:00:00Z","projectId":"%s","rateAmount":200}
                """.formatted(projectP));
        assertThat(e.getRateAmount()).isEqualByComparingTo("200");   // explicit body wins
        assertThat(e.getCostRateAmount()).isEqualByComparingTo("60"); // assignment override
    }
}
