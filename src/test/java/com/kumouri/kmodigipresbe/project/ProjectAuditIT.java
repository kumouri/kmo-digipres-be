package com.kumouri.kmodigipresbe.project;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.audit.AuditEventRepository;
import com.kumouri.kmodigipresbe.audit.AuditOp;
import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.project.Task;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.project.MilestoneRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.repository.project.TaskRepository;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-C7: All new entities (Project, Milestone, Task) are Auditable.
 * After create → a CREATE AuditEvent exists.
 * After status change → an UPDATE AuditEvent exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class ProjectAuditIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired AuditEventRepository auditEvents;
    @Autowired ProjectRepository projectRepo;
    @Autowired MilestoneRepository milestoneRepo;
    @Autowired TaskRepository taskRepo;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), AuditEvent.class).block();
        mongo.remove(new Query(), Task.class).block();
        mongo.remove(new Query(), Milestone.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("audit-" + tenantId)
                .displayName("Audit Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@audit.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    @Test
    void createProjectWritesCreateAuditEvent() {
        Project project = web.post().uri("/projects")
                .header("Authorization", token)
                .bodyValue(Project.builder().name("Audit Project").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class).returnResult().getResponseBody();

        assertThat(project).isNotNull();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                tenantId, "Project", project.getId()).collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getOp()).isEqualTo(AuditOp.CREATE);
    }

    @Test
    void createMilestoneWritesCreateAuditEvent() {
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-A01")
                .name("Audit MS Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        Milestone ms = web.post().uri("/milestones/by-project/" + project.getId())
                .header("Authorization", token)
                .bodyValue(Milestone.builder().name("Audit Milestone").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Milestone.class).returnResult().getResponseBody();

        assertThat(ms).isNotNull();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                tenantId, "Milestone", ms.getId()).collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getOp()).isEqualTo(AuditOp.CREATE);
    }

    @Test
    void createTaskWritesCreateAuditEvent() {
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-A02")
                .name("Audit Task Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        Task task = web.post().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", token)
                .bodyValue(Task.builder().title("Audit Task").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Task.class).returnResult().getResponseBody();

        assertThat(task).isNotNull();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                tenantId, "Task", task.getId()).collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getOp()).isEqualTo(AuditOp.CREATE);
    }

    @Test
    void projectStatusChangeWritesUpdateAuditEvent() {
        Project project = web.post().uri("/projects")
                .header("Authorization", token)
                .bodyValue(Project.builder().name("Update Audit Project").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class).returnResult().getResponseBody();

        web.post().uri("/projects/" + project.getId() + "/status?target=ACTIVE")
                .header("Authorization", token).exchange()
                .expectStatus().isOk();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                tenantId, "Project", project.getId()).collectList().block();

        // Should have at least CREATE + UPDATE
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events).anyMatch(e -> e.getOp() == AuditOp.UPDATE);
    }

    @Test
    void taskStatusChangeWritesUpdateAuditEvent() {
        Project project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-A03")
                .name("Task Audit Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        Task task = web.post().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", token)
                .bodyValue(Task.builder().title("Audit Status Task").build())
                .exchange()
                .expectBody(Task.class).returnResult().getResponseBody();

        web.post().uri("/tasks/" + task.getId() + "/status?target=IN_PROGRESS")
                .header("Authorization", token).exchange().expectStatus().isOk();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                tenantId, "Task", task.getId()).collectList().block();
        assertThat(events).anyMatch(e -> e.getOp() == AuditOp.UPDATE);
    }
}
