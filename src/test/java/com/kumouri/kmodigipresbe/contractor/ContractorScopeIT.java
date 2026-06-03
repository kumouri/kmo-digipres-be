package com.kumouri.kmodigipresbe.contractor;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.contractor.ProjectAssignment;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.project.Task;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — J2 contractor scoped-access (the headline IT). A CONTRACTOR logs into the same
 * staff app but is scoped to (a) their actively-assigned projects, (b) those projects' tasks,
 * (c) their OWN time/expenses, and (d) read-only the assigned project's linked client. They
 * are pushed off the broad staff readers (projects/contacts/deals/quotes/invoices list,
 * cross-user time) onto {@code /me/contractor/**} with 4135; a foreign-userId write is 4134;
 * an unassigned project / un-owned entity is the same-404 4132/4133 (no enumeration oracle).
 * A plain STAFF (non-contractor) token is NOT denied the broad readers (regression guard).
 *
 * <p>Mirrors the {@code ProjectAssignmentCrudIT}/{@code RateStampingIT}/{@code PortalInvoicesIT}
 * harness: @SpringBootTest RANDOM_PORT + WebTestClient + Testcontainers Mongo + the
 * quartz-proof-job-off property; seed a Tenant + ADMIN + CONTRACTOR (+ plain STAFF) + an
 * assigned and an unassigned Project + a ProjectAssignment; JWTs via JwtTokenService.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class ContractorScopeIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAssignmentRepository assignments;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID contractorId;
    private UUID otherUserId;
    private UUID assignedProjectId;
    private UUID unassignedProjectId;
    private UUID assignedTaskId;
    private UUID clientContactId;
    private UUID clientCompanyId;

    private String contractorToken;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("cs-it-" + tenantId)
                .displayName("CS IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        // The contractor under test (roles={STAFF,CONTRACTOR}).
        contractorId = UUID.randomUUID();
        User contractor = User.builder().id(contractorId).tenantId(tenantId).email("c@cs.test")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build();
        users.save(contractor).block();
        contractorToken = "Bearer " + jwt.mint(contractor);

        // An ADMIN (also a staff non-contractor for the regression guard would be ADMIN-heavy;
        // we use a dedicated plain-STAFF below).
        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("a@cs.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        // A plain STAFF (non-contractor) — the broad-reader regression guard.
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("s@cs.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        // Another user whose time the contractor must never see.
        otherUserId = UUID.randomUUID();
        users.save(User.builder().id(otherUserId).tenantId(tenantId).email("o@cs.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build()).block();

        // The linked client (contact + company) of the assigned project.
        clientCompanyId = UUID.randomUUID();
        mongo.save(Company.builder().id(clientCompanyId).tenantId(tenantId)
                .name("Acme Co").build()).block();
        clientContactId = UUID.randomUUID();
        mongo.save(Contact.builder().id(clientContactId).tenantId(tenantId)
                .firstName("Carol").lastName("Client").companyId(clientCompanyId)
                .emails(List.of(new EmailContact("carol@acme.test")))
                .phones(List.of(PhoneNumber.builder().number("+13145550100").label("work").build()))
                .build()).block();

        // The assigned project (with a primary contact + company) and one task.
        assignedProjectId = UUID.randomUUID();
        projects.save(Project.builder().id(assignedProjectId).tenantId(tenantId).code("PRJ-CS-001")
                .name("Assigned Project").status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(clientContactId).companyId(clientCompanyId)
                .dealId(UUID.randomUUID()).ownerId(UUID.randomUUID()).build()).block();

        assignedTaskId = UUID.randomUUID();
        mongo.save(Task.builder().id(assignedTaskId).tenantId(tenantId).projectId(assignedProjectId)
                .title("Build the thing").status(Task.TaskStatus.TODO).orderIndex(0).build()).block();

        // The unassigned project — the contractor must NOT see this one.
        unassignedProjectId = UUID.randomUUID();
        projects.save(Project.builder().id(unassignedProjectId).tenantId(tenantId).code("PRJ-CS-002")
                .name("Unassigned Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        // Active assignment of the contractor to the assigned project only.
        assignments.save(ProjectAssignment.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .projectId(assignedProjectId).userId(contractorId)
                .role("Engineer").active(true).build()).block();

        // A time entry owned by ANOTHER user — must never surface to the contractor.
        mongo.save(TimeEntry.builder().id(UUID.randomUUID()).tenantId(tenantId).userId(otherUserId)
                .startedAt(Instant.parse("2026-05-18T09:00:00Z"))
                .endedAt(Instant.parse("2026-05-18T10:00:00Z")).durationSeconds(3600L)
                .billingStatus(TimeEntry.BillingStatus.UNBILLED).build()).block();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), ProjectAssignment.class).block();
        mongo.remove(new Query(), Task.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Company.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // -------------------------------------------------------------------------
    // (a) projects: sees ONLY assigned
    // -------------------------------------------------------------------------

    @Test
    void contractorListsOnlyAssignedProjects() {
        web.get().uri("/me/contractor/projects")
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].code").isEqualTo("PRJ-CS-001")
                // projection: no staff-internal/sales fields leak
                .jsonPath("$[0].tenantId").doesNotExist()
                .jsonPath("$[0].dealId").doesNotExist()
                .jsonPath("$[0].ownerId").doesNotExist()
                .jsonPath("$[0].primaryContactId").doesNotExist();
    }

    @Test
    void contractorGetsAssignedProject() {
        web.get().uri("/me/contractor/projects/{id}", assignedProjectId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("PRJ-CS-001")
                .jsonPath("$.name").isEqualTo("Assigned Project");
    }

    @Test
    void contractorGetUnassignedProjectIsSame404With4132() {
        web.get().uri("/me/contractor/projects/{id}", unassignedProjectId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4132);
    }

    @Test
    void contractorGetNonexistentProjectIsSame404With4132() {
        // A wholly-unknown id is indistinguishable from an existing-but-unassigned one.
        web.get().uri("/me/contractor/projects/{id}", UUID.randomUUID())
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4132);
    }

    // -------------------------------------------------------------------------
    // (b) tasks within an assigned project
    // -------------------------------------------------------------------------

    @Test
    void contractorListsTasksOfAssignedProject() {
        web.get().uri("/me/contractor/projects/{id}/tasks", assignedProjectId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].title").isEqualTo("Build the thing")
                .jsonPath("$[0].tenantId").doesNotExist()
                .jsonPath("$[0].assigneeUserId").doesNotExist();
    }

    @Test
    void contractorTasksOfUnassignedProjectIs4132() {
        web.get().uri("/me/contractor/projects/{id}/tasks", unassignedProjectId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4132);
    }

    // -------------------------------------------------------------------------
    // (d) read-only linked client
    // -------------------------------------------------------------------------

    @Test
    void contractorGetsAssignedProjectClient() {
        web.get().uri("/me/contractor/projects/{id}/client", assignedProjectId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.contactName").isEqualTo("Carol Client")
                .jsonPath("$.contactEmail").isEqualTo("carol@acme.test")
                .jsonPath("$.contactPhone").isEqualTo("+13145550100")
                .jsonPath("$.companyName").isEqualTo("Acme Co")
                // nothing else from the CRM
                .jsonPath("$.tenantId").doesNotExist()
                .jsonPath("$.leadScore").doesNotExist()
                .jsonPath("$.tags").doesNotExist();
    }

    @Test
    void contractorClientOfUnassignedProjectIs4132() {
        web.get().uri("/me/contractor/projects/{id}/client", unassignedProjectId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4132);
    }

    // -------------------------------------------------------------------------
    // broad staff readers — contractor pushed off with 4135
    // -------------------------------------------------------------------------

    @Test
    void contractorDeniedBroadProjectsList() {
        denied("/projects");
    }

    @Test
    void contractorDeniedContactsList() {
        denied("/contacts");
    }

    @Test
    void contractorDeniedDealsList() {
        denied("/deals");
    }

    @Test
    void contractorDeniedQuotesList() {
        denied("/quotes");
    }

    @Test
    void contractorDeniedInvoicesList() {
        denied("/invoices");
    }

    @Test
    void contractorDeniedWeeklyCrossUserTime() {
        web.get().uri(uri -> uri.path("/time-entries/weekly")
                        .queryParam("from", "2026-05-01T00:00:00Z")
                        .queryParam("to", "2026-05-31T00:00:00Z")
                        .queryParam("userId", otherUserId.toString())
                        .build())
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4135);
    }

    @Test
    void contractorDeniedCrossUserExpenseList() {
        web.get().uri("/expenses/by-user/{id}", otherUserId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4135);
    }

    private void denied(String path) {
        web.get().uri(path)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4135);
    }

    // -------------------------------------------------------------------------
    // plain STAFF (non-contractor) regression guard — NOT denied
    // -------------------------------------------------------------------------

    @Test
    void plainStaffNotDeniedBroadReaders() {
        // /projects, /contacts, /deals, /quotes, /invoices all return 200 for plain STAFF.
        for (String path : List.of("/projects", "/contacts", "/deals", "/quotes", "/invoices")) {
            web.get().uri(path)
                    .header("Authorization", staffToken)
                    .exchange()
                    .expectStatus().isOk();
        }
        web.get().uri("/expenses/by-user/{id}", otherUserId)
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk();
    }

    // -------------------------------------------------------------------------
    // (c) own time — self-scoped reads + writes
    // -------------------------------------------------------------------------

    @Test
    void contractorLogTimeWithForeignUserIdIs4134() {
        web.post().uri("/me/contractor/time")
                .header("Authorization", contractorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"userId":"%s","startedAt":"2026-05-19T09:00:00Z","endedAt":"2026-05-19T10:00:00Z"}
                        """.formatted(otherUserId))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4134);
    }

    @Test
    void contractorLogTimeWithNoUserIdLogsAsSelfAndListsOnlyOwn() {
        // No userId in the body → stamped as self → 201.
        web.post().uri("/me/contractor/time")
                .header("Authorization", contractorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"startedAt":"2026-05-19T09:00:00Z","endedAt":"2026-05-19T11:00:00Z","projectId":"%s"}
                        """.formatted(assignedProjectId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.userId").isEqualTo(contractorId.toString());

        // The self-scoped list returns ONLY the contractor's own entry — never the
        // pre-seeded entry owned by otherUserId.
        web.get().uri("/me/contractor/time")
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].userId").isEqualTo(contractorId.toString());
    }

    // -------------------------------------------------------------------------
    // not-a-contractor guard (4130) on the scoped surface
    // -------------------------------------------------------------------------

    @Test
    void nonContractorStaffCannotUseContractorSurface() {
        // A plain STAFF (no CONTRACTOR role) hitting /me/contractor/** is 4130/403.
        web.get().uri("/me/contractor/projects")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4130);
    }
}
