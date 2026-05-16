package com.kumouri.kmodigipresbe.project;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.project.Task;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
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
 * AC-C7 (partial): Kanban status moves for Tasks.
 * Also verifies DONE sets completedAt and a reverse move clears it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class TaskKanbanIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ProjectRepository projectRepo;
    @Autowired TaskRepository taskRepo;

    private UUID tenantId;
    private String token;
    private Project project;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Task.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("kanban-" + tenantId)
                .displayName("Kanban Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@kanban.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);

        project = projectRepo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).code("PRJ-2026-K01")
                .name("Kanban Project").status(Project.ProjectStatus.ACTIVE).build()).block();
    }

    @Test
    void createTaskDefaultsTodo() {
        web.post().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", token)
                .bodyValue(Task.builder().title("New Task").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("TODO")
                .jsonPath("$.priority").isEqualTo("MEDIUM");
    }

    @Test
    void todoToInProgressToBlockedToDone() {
        Task task = web.post().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", token)
                .bodyValue(Task.builder().title("Journey Task").build())
                .exchange()
                .expectBody(Task.class).returnResult().getResponseBody();

        // TODO → IN_PROGRESS
        web.post().uri("/tasks/" + task.getId() + "/status?target=IN_PROGRESS")
                .header("Authorization", token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("IN_PROGRESS");

        // IN_PROGRESS → BLOCKED
        web.post().uri("/tasks/" + task.getId() + "/status?target=BLOCKED")
                .header("Authorization", token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("BLOCKED");

        // BLOCKED → DONE (should set completedAt)
        Task done = web.post().uri("/tasks/" + task.getId() + "/status?target=DONE")
                .header("Authorization", token).exchange()
                .expectStatus().isOk()
                .expectBody(Task.class).returnResult().getResponseBody();

        assertThat(done.getCompletedAt()).isNotNull();
    }

    @Test
    void moveBackFromDoneClearsCompletedAt() {
        Task task = taskRepo.save(Task.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(project.getId())
                .title("Done Task").status(Task.TaskStatus.DONE)
                .completedAt(java.time.Instant.now()).build()).block();

        Task moved = web.post().uri("/tasks/" + task.getId() + "/status?target=IN_PROGRESS")
                .header("Authorization", token).exchange()
                .expectStatus().isOk()
                .expectBody(Task.class).returnResult().getResponseBody();

        assertThat(moved.getCompletedAt()).isNull();
    }

    @Test
    void listTasksByProject() {
        taskRepo.save(Task.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .projectId(project.getId()).title("T1").status(Task.TaskStatus.TODO).build()).block();
        taskRepo.save(Task.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .projectId(project.getId()).title("T2").status(Task.TaskStatus.IN_PROGRESS).build()).block();

        web.get().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", token).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void titleBlankReturns400() {
        web.post().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", token)
                .bodyValue(Task.builder().title("").build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3421);
    }
}
