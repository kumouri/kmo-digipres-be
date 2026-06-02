package com.kumouri.kmodigipresbe.contractor;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contractor.ProjectAssignment;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — ProjectAssignment CRUD: ADMIN-gated, idempotent create (201 first / 200 repeat),
 * list, rate update, and soft-delete (active=false, the row and its rate history survive).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class ProjectAssignmentCrudIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID memberId;
    private UUID projectP;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("pa-it-" + tenantId)
                .displayName("PA IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@pa.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@pa.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        memberId = UUID.randomUUID();
        users.save(User.builder().id(memberId).tenantId(tenantId).email("member@pa.test")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build()).block();

        projectP = UUID.randomUUID();
        projects.save(Project.builder().id(projectP).tenantId(tenantId).code("PRJ-PA-001")
                .name("PA Project").status(Project.ProjectStatus.ACTIVE).build()).block();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), ProjectAssignment.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private String body() {
        return """
                {"userId":"%s","billRateOverride":80,"costRateOverride":40,"role":"Engineer"}
                """.formatted(memberId);
    }

    private ProjectAssignment create() {
        return web.post().uri("/projects/{p}/assignments", projectP)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProjectAssignment.class)
                .returnResult().getResponseBody();
    }

    @Test
    void createIsIdempotentOnProjectAndUser() {
        ProjectAssignment created = create();
        assertThat(created).isNotNull();
        assertThat(created.getUserId()).isEqualTo(memberId);
        assertThat(created.getProjectId()).isEqualTo(projectP);
        assertThat(created.isActive()).isTrue();
        assertThat(created.getBillRateOverride()).isEqualByComparingTo("80");
        assertThat(created.getCostRateOverride()).isEqualByComparingTo("40");

        // A repeat with a fresh idempotency key exercises the DOMAIN idempotency:
        // returns 200 with the same assignment id (not a duplicate row).
        ProjectAssignment again = web.post().uri("/projects/{p}/assignments", projectP)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProjectAssignment.class)
                .returnResult().getResponseBody();
        assertThat(again).isNotNull();
        assertThat(again.getId()).isEqualTo(created.getId());

        assertThat(mongo.findAll(ProjectAssignment.class).collectList().block()).hasSize(1);
    }

    @Test
    void listUpdateRatesAndSoftDelete() {
        ProjectAssignment created = create();
        UUID id = created.getId();

        web.get().uri("/projects/{p}/assignments", projectP)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProjectAssignment.class).hasSize(1);

        web.put().uri("/projects/{p}/assignments/{id}", projectP, id)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"billRateOverride":90}
                        """)
                .exchange()
                .expectStatus().isOk();
        ProjectAssignment afterUpdate = mongo.findAll(ProjectAssignment.class).collectList().block().get(0);
        assertThat(afterUpdate.getBillRateOverride()).isEqualByComparingTo("90");
        assertThat(afterUpdate.getCostRateOverride()).isEqualByComparingTo("40"); // untouched

        web.delete().uri("/projects/{p}/assignments/{id}", projectP, id)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isNoContent();

        List<ProjectAssignment> after = mongo.findAll(ProjectAssignment.class).collectList().block();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).isActive()).isFalse();

        // Re-assigning reactivates the soft-deleted row (200, same id, active=true again).
        ProjectAssignment reactivated = web.post().uri("/projects/{p}/assignments", projectP)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProjectAssignment.class)
                .returnResult().getResponseBody();
        assertThat(reactivated.getId()).isEqualTo(id);
        assertThat(reactivated.isActive()).isTrue();
    }

    @Test
    void nonAdminCannotAssign() {
        web.post().uri("/projects/{p}/assignments", projectP)
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void assignToMissingProjectReturns404With4101() {
        web.post().uri("/projects/{p}/assignments", UUID.randomUUID())
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4101);
    }
}
