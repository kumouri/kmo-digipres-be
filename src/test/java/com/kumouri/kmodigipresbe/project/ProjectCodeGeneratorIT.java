package com.kumouri.kmodigipresbe.project;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.project.Project;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Project code generation: format PRJ-{year}-{seq:03}, per-(tenant,year)
 * independence, and January reset simulation (AC-C3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class ProjectCodeGeneratorIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantAId;
    private UUID tenantBId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Project.class).block();
        mongo.dropCollection("project_code_counters").block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("cg-a-" + tenantAId)
                .displayName("CG Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("cg-b-" + tenantBId)
                .displayName("CG Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@cg.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(userA).block();
        tokenA = "Bearer " + jwt.mint(userA);

        User userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@cg.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(userB).block();
        tokenB = "Bearer " + jwt.mint(userB);
    }

    @Test
    void threeProjectsForTenantAreSequential() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String expectedPrefix = "PRJ-" + year + "-";

        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String code = web.post().uri("/projects")
                    .header("Authorization", tokenA)
                    .bodyValue(Project.builder().name("Project " + i).build())
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(Project.class)
                    .returnResult().getResponseBody().getCode();
            codes.add(code);
        }

        assertThat(codes).hasSize(3);
        assertThat(codes).containsExactly(
                expectedPrefix + "001",
                expectedPrefix + "002",
                expectedPrefix + "003");
    }

    @Test
    void differentTenantsHaveIndependentCounters() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String expectedCode = "PRJ-" + year + "-001";

        // Tenant A creates a project (seq 1)
        String codeA = web.post().uri("/projects")
                .header("Authorization", tokenA)
                .bodyValue(Project.builder().name("A Project").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody().getCode();
        assertThat(codeA).isEqualTo(expectedCode);

        // Tenant B's first project is also seq 001 (independent counter)
        String codeB = web.post().uri("/projects")
                .header("Authorization", tokenB)
                .bodyValue(Project.builder().name("B Project").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody().getCode();
        assertThat(codeB).isEqualTo(expectedCode);
    }

    @Test
    void januaryResetSimulation() {
        // Simulate year rollover: seed a counter doc for prior year at seq=7,
        // then the current year's first project should be PRJ-{thisYear}-001, not 008.
        int thisYear = LocalDate.now(ZoneOffset.UTC).getYear();
        int lastYear = thisYear - 1;

        // Seed a "previous year" counter for tenant A (seq at 7)
        mongo.insert(
                org.bson.Document.parse("{\"_id\":\"" + tenantAId + ":" + lastYear + "\",\"seq\":7}"),
                "project_code_counters").block();

        // First project in current year should be 001 (new _id = tenantId:thisYear)
        String code = web.post().uri("/projects")
                .header("Authorization", tokenA)
                .bodyValue(Project.builder().name("New Year Project").build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody().getCode();

        assertThat(code).isEqualTo("PRJ-" + thisYear + "-001");
    }

}
