package com.kumouri.kmodigipresbe.contract;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.7 — ContractTemplateLifecycleIT: CRUD + validation (AC-F1/F2/F3 foundational).
 *
 * <p>Asserts: POST 201 / 3701 (blank name) / 3702 (blank bodyTemplate); CRUD paths;
 * ADMIN-guarded DELETE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ContractTemplateLifecycleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("ctmpl-" + tenantId)
                .displayName("Template Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@ctmpl.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@ctmpl.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    // -------------------------------------------------------------------------
    // POST /contract-templates → 201
    // -------------------------------------------------------------------------

    @Test
    void createTemplate_201_withValidBody() {
        web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"SOW Template","bodyTemplate":"Body {{name}}","kind":"SOW"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.name").isEqualTo("SOW Template")
                .jsonPath("$.kind").isEqualTo("SOW")
                .jsonPath("$.active").isEqualTo(true);
    }

    @Test
    void createTemplate_400_3701_blankName() {
        web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"","bodyTemplate":"Body content"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3701);
    }

    @Test
    void createTemplate_400_3702_blankBodyTemplate() {
        web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Valid Name","bodyTemplate":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3702);
    }

    // -------------------------------------------------------------------------
    // GET /contract-templates and /{id}
    // -------------------------------------------------------------------------

    @Test
    void listTemplates_returnsAll() {
        // Create two templates
        web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"T1\",\"bodyTemplate\":\"B1\"}")
                .exchange().expectStatus().isCreated();
        web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"T2\",\"bodyTemplate\":\"B2\"}")
                .exchange().expectStatus().isCreated();

        web.get().uri("/contract-templates")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ContractTemplate.class)
                .hasSize(2);
    }

    @Test
    void getTemplate_byId() {
        String id = web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Get Test\",\"bodyTemplate\":\"Body\"}")
                .exchange().expectStatus().isCreated()
                .returnResult(ContractTemplate.class)
                .getResponseBody().blockFirst().getId().toString();

        web.get().uri("/contract-templates/" + id)
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(id);
    }

    // -------------------------------------------------------------------------
    // PUT /contract-templates/{id}
    // -------------------------------------------------------------------------

    @Test
    void updateTemplate_changesName() {
        String id = web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Original\",\"bodyTemplate\":\"Body\"}")
                .exchange().expectStatus().isCreated()
                .returnResult(ContractTemplate.class)
                .getResponseBody().blockFirst().getId().toString();

        web.put().uri("/contract-templates/" + id)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Updated Name\",\"bodyTemplate\":\"New Body\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Updated Name");
    }

    // -------------------------------------------------------------------------
    // DELETE /contract-templates/{id} — ADMIN only
    // -------------------------------------------------------------------------

    @Test
    void deleteTemplate_adminOk_204() {
        String id = web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"To Delete\",\"bodyTemplate\":\"Body\"}")
                .exchange().expectStatus().isCreated()
                .returnResult(ContractTemplate.class)
                .getResponseBody().blockFirst().getId().toString();

        web.delete().uri("/contract-templates/" + id)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isNoContent();

        // Confirm gone
        web.get().uri("/contract-templates/" + id)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteTemplate_staffForbidden_403() {
        String id = web.post().uri("/contract-templates")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Protected\",\"bodyTemplate\":\"Body\"}")
                .exchange().expectStatus().isCreated()
                .returnResult(ContractTemplate.class)
                .getResponseBody().blockFirst().getId().toString();

        web.delete().uri("/contract-templates/" + id)
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden();
    }
}
