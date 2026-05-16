package com.kumouri.kmodigipresbe.project;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.project.Task;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic CRUD and status lifecycle for Project, Milestone, Task (Phase C).
 * Covers entity creation, update, status transitions, DELETE gating.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class ProjectLifecycleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;
    private String adminToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), com.kumouri.kmodigipresbe.model.project.Milestone.class).block();
        mongo.remove(new Query(), Task.class).block();
        mongo.remove(new Query(), com.kumouri.kmodigipresbe.model.billing.Invoice.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("prj-lc-" + tenantId)
                .displayName("Project LC").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@prj.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@prj.test").roles(Set.of("ADMIN", "STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);
    }

    @Test
    void createProjectReturns201WithCode() {
        web.post().uri("/projects")
                .header("Authorization", staffToken)
                .bodyValue(Project.builder().name("Test Project").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.name").isEqualTo("Test Project")
                .jsonPath("$.code").value(code -> assertThat((String) code).matches("PRJ-\\d{4}-\\d{3}"))
                .jsonPath("$.status").isEqualTo("PLANNING");
    }

    @Test
    void createProjectWithBlankNameReturns400() {
        web.post().uri("/projects")
                .header("Authorization", staffToken)
                .bodyValue(Project.builder().name("").build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3401);
    }

    @Test
    void getProjectReturns200() {
        String id = web.post().uri("/projects")
                .header("Authorization", staffToken)
                .bodyValue(Project.builder().name("Get Test").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody().getId().toString();

        web.get().uri("/projects/" + id)
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id);
    }

    @Test
    void statusTransitionToActive() {
        Project project = web.post().uri("/projects")
                .header("Authorization", staffToken)
                .bodyValue(Project.builder().name("Status Test").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody();

        web.post().uri("/projects/" + project.getId() + "/status?target=ACTIVE")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ACTIVE");
    }

    @Test
    void deleteRequiresAdminRole() {
        Project project = web.post().uri("/projects")
                .header("Authorization", staffToken)
                .bodyValue(Project.builder().name("Delete Test").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody();

        // STAFF cannot delete
        web.delete().uri("/projects/" + project.getId())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden();

        // ADMIN can delete
        web.delete().uri("/projects/" + project.getId())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void unauthenticatedRequestsRejected() {
        web.get().uri("/projects").exchange().expectStatus().isUnauthorized();
        web.post().uri("/projects").bodyValue(Project.builder().name("X").build())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void createTaskAndChangeStatus() {
        Project project = web.post().uri("/projects")
                .header("Authorization", staffToken)
                .bodyValue(Project.builder().name("Task Project").build())
                .exchange()
                .expectBody(Project.class).returnResult().getResponseBody();

        Task task = web.post().uri("/tasks/by-project/" + project.getId())
                .header("Authorization", staffToken)
                .bodyValue(Task.builder().title("My Task").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Task.class).returnResult().getResponseBody();

        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(Task.TaskStatus.TODO);

        web.post().uri("/tasks/" + task.getId() + "/status?target=IN_PROGRESS")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("IN_PROGRESS");
    }
}
