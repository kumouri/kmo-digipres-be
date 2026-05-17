package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.UUID;

/**
 * AC-G4 / AC-G2 — portal project access-control isolation.
 *
 * <p>Seeds Tenant A (contactA w/ companyA, contactA2 w/o company link) and
 * Tenant B (contactB). Proves cross-tenant AND cross-contact (no-company) isolation
 * on GET /portal/me/projects + GET /portal/me/projects/{id} + the files surface.
 * Mirrors the {@link PortalInvoicesIT} cross-tenant+cross-contact shape exactly
 * (§9 #3 / shard-safe: no @MockBean, self-clean @BeforeEach).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalProjectsAccessIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantAId;
    private UUID tenantBId;
    private User userA;
    private User userANoCompany;
    private User userB;
    private Contact contactA;
    private Contact contactA2;
    private UUID companyA;
    private Project projectA;        // owned by contactA (via primaryContactId)
    private Project projectACompany; // owned by contactA (via companyA)
    private Project projectB;        // Tenant B — must never leak to A
    private Attachment fileOnProjectA;

    @BeforeEach
    void seed() {
        // Clean all collections touched by this IT.
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("prj-a-" + tenantAId)
                .displayName("Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("prj-b-" + tenantBId)
                .displayName("Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        companyA = UUID.randomUUID();

        // ContactA — has companyId (linked to Tenant A)
        contactA = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("Alice").lastName("A").companyId(companyA).build();
        mongo.save(contactA).block();

        // ContactA2 — NO company link (should see nothing of contactA's data)
        contactA2 = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("NoCoA2").lastName("A2").build();
        mongo.save(contactA2).block();

        // ContactB — Tenant B
        Contact contactB = Contact.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .firstName("Bob").lastName("B").build();
        mongo.save(contactB).block();

        // Users
        userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@prj.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();

        userANoCompany = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a2@prj.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA2.getId()).build();
        users.save(userANoCompany).block();

        userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@prj.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId()).build();
        users.save(userB).block();

        // Projects
        projectA = Project.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .code("PRJ-2026-001").name("Project A Contact")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(contactA.getId())
                .build();
        mongo.save(projectA).block();

        projectACompany = Project.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .code("PRJ-2026-002").name("Project A Company")
                .status(Project.ProjectStatus.PLANNING)
                .companyId(companyA)
                .build();
        mongo.save(projectACompany).block();

        // An unrelated project in Tenant A (different contact, no company) — must NOT appear for userA
        Project projectAOther = Project.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .code("PRJ-2026-003").name("Project A Other")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(UUID.randomUUID())
                .build();
        mongo.save(projectAOther).block();

        projectB = Project.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .code("PRJ-2026-001").name("Project B")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(contactB.getId())
                .build();
        mongo.save(projectB).block();

        // A file attached to projectA (subjectType="PROJECT")
        fileOnProjectA = Attachment.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .subjectType("PROJECT").subjectId(projectA.getId())
                .filename("report.pdf").contentType("application/pdf").sizeBytes(1024L)
                .storageRef("tenants/" + tenantAId + "/projects/" + projectA.getId() + "/report.pdf")
                .build();
        mongo.save(fileOnProjectA).block();
    }

    // ─── List tests ──────────────────────────────────────────────────────────

    @Test
    void unauthenticatedRejected() {
        web.get().uri("/portal/me/projects").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void userA_seesOwnContactAndCompanyProjects() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/projects")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void crossTenantIsolation_userB_seesOnlyOwnProjects() {
        String token = jwt.mint(userB);
        web.get().uri("/portal/me/projects")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("Project B");
    }

    @Test
    void contactWithNoCompany_seesNone() {
        // contactA2 has no primaryContactId on any project and no companyId
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/projects")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    // ─── Single-project GET tests ────────────────────────────────────────────

    @Test
    void userA_canGetOwnProject() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/projects/" + projectA.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(projectA.getId().toString())
                .jsonPath("$.tenantId").doesNotExist();
    }

    @Test
    void crossTenantProject_returns3803() {
        // userA tries to access Tenant B's project
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/projects/" + projectB.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3803);
    }

    @Test
    void crossContactProject_returns3803() {
        // userANoCompany tries to access contactA's project
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/projects/" + projectA.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3803);
    }

    // ─── Project files tests ─────────────────────────────────────────────────

    @Test
    void userA_canListFilesForOwnedProject() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/projects/" + projectA.getId() + "/files")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].filename").isEqualTo("report.pdf")
                .jsonPath("$[0].storageRef").doesNotExist(); // raw ref must not be exposed
    }

    @Test
    void crossContactProject_filesReturn3803() {
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/projects/" + projectA.getId() + "/files")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3803);
    }

    @Test
    void crossTenantProject_filesReturn3803() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/projects/" + projectB.getId() + "/files")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3803);
    }

    @Test
    void anotherContactFile_downloadReturns3807() {
        // userANoCompany tries to download a file linked to contactA's project
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/projects/" + projectA.getId() + "/files/"
                + fileOnProjectA.getId() + "/download")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3803); // blocked at project ownership gate
    }
}
