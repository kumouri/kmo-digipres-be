package com.kumouri.kmodigipresbe.contractor;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.contractor.TimesheetRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.TimeEntryRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — J3 timesheet submit→approve lifecycle (the headline IT). A CONTRACTOR submits
 * their own OPEN period; an ADMIN approves (member entries flip {@code approved=true}) or
 * rejects with a reason (member entries flip back to {@code false}); the contractor reopens
 * a rejected period. Illegal transitions are 4150; a blank reject reason is 4151; a
 * non-admin approve is 1800 (RoleGuard); a contractor reaching another contractor's
 * timesheet is the same-404 4133.
 *
 * <p>Mirrors the {@code ContractorScopeIT}/{@code InvoiceFromTimeIT} harness: @SpringBootTest
 * RANDOM_PORT + WebTestClient + Testcontainers Mongo + the quartz-proof-job-off property;
 * seed a Tenant + ADMIN + CONTRACTOR (+ a second CONTRACTOR) + a Timesheet with member
 * TimeEntries; JWTs via JwtTokenService.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class TimesheetLifecycleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired TimesheetRepository timesheets;
    @Autowired TimeEntryRepository timeEntries;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID contractorId;
    private UUID otherContractorId;

    private String contractorToken;
    private String otherContractorToken;
    private String adminToken;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("tsl-it-" + tenantId)
                .displayName("TSL IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        contractorId = UUID.randomUUID();
        User contractor = User.builder().id(contractorId).tenantId(tenantId).email("c@tsl.test")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build();
        users.save(contractor).block();
        contractorToken = "Bearer " + jwt.mint(contractor);

        otherContractorId = UUID.randomUUID();
        User other = User.builder().id(otherContractorId).tenantId(tenantId).email("c2@tsl.test")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build();
        users.save(other).block();
        otherContractorToken = "Bearer " + jwt.mint(other);

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("a@tsl.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Timesheet.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // -------------------------------------------------------------------------
    // Helpers — seed a period + member entries in a given status
    // -------------------------------------------------------------------------

    /** Seeds a period in the first ISO week (the common single-sheet case). */
    private UUID seedTimesheet(UUID userId, Timesheet.Status status) {
        return seedTimesheet(userId, status, 0);
    }

    /**
     * Seeds a period {@code weekOffset} weeks after 2026-05-18. Distinct offsets keep the
     * unique {@code (tenantId, userId, periodStart)} index happy when one user owns several
     * periods.
     */
    private UUID seedTimesheet(UUID userId, Timesheet.Status status, int weekOffset) {
        UUID id = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 5, 18).plusWeeks(weekOffset);
        timesheets.save(Timesheet.builder().id(id).tenantId(tenantId).userId(userId)
                .periodStart(start).periodEnd(start.plusDays(6))
                .status(status).build()).block();
        return id;
    }

    /** A stopped, billable, UNBILLED member entry on the given timesheet (approved=false). */
    private UUID seedMemberEntry(UUID userId, UUID timesheetId) {
        UUID id = UUID.randomUUID();
        Instant s = Instant.parse("2026-05-19T09:00:00Z");
        timeEntries.save(TimeEntry.builder().id(id).tenantId(tenantId).userId(userId)
                .timesheetId(timesheetId).startedAt(s).endedAt(s.plusSeconds(3600))
                .durationSeconds(3600L).billable(true)
                .billingStatus(TimeEntry.BillingStatus.UNBILLED).approved(false).build()).block();
        return id;
    }

    private boolean entryApproved(UUID entryId) {
        return Boolean.TRUE.equals(timeEntries.findByTenantIdAndId(tenantId, entryId)
                .map(TimeEntry::isApproved).block());
    }

    // -------------------------------------------------------------------------
    // Contractor submit
    // -------------------------------------------------------------------------

    @Test
    void contractorSubmitsOpenTimesheet() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.OPEN);

        web.post().uri("/me/contractor/timesheets/{id}/submit", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUBMITTED")
                .jsonPath("$.submittedAt").exists()
                // projection drops tenantId
                .jsonPath("$.tenantId").doesNotExist();

        assertThat(timesheets.findByTenantIdAndId(tenantId, tsId).block().getStatus())
                .isEqualTo(Timesheet.Status.SUBMITTED);
    }

    // -------------------------------------------------------------------------
    // Admin approve → member entries approved=true
    // -------------------------------------------------------------------------

    @Test
    void adminApprovesSubmittedTimesheetAndFlipsMemberEntries() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.SUBMITTED);
        UUID e1 = seedMemberEntry(contractorId, tsId);
        UUID e2 = seedMemberEntry(contractorId, tsId);

        web.post().uri("/timesheets/{id}/approve", tsId)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("APPROVED")
                .jsonPath("$.approvedBy").exists()
                .jsonPath("$.approvedAt").exists();

        assertThat(entryApproved(e1)).isTrue();
        assertThat(entryApproved(e2)).isTrue();
    }

    @Test
    void adminApproveExcludesInvoicedEntriesFromFlip() {
        // An entry that is already INVOICED must NOT be touched by the approve flip.
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.SUBMITTED);
        UUID unbilled = seedMemberEntry(contractorId, tsId);
        UUID invoiced = UUID.randomUUID();
        Instant s = Instant.parse("2026-05-20T09:00:00Z");
        timeEntries.save(TimeEntry.builder().id(invoiced).tenantId(tenantId).userId(contractorId)
                .timesheetId(tsId).startedAt(s).endedAt(s.plusSeconds(3600)).durationSeconds(3600L)
                .billable(true).billingStatus(TimeEntry.BillingStatus.INVOICED)
                .invoicedInvoiceId(UUID.randomUUID()).approved(false).build()).block();

        web.post().uri("/timesheets/{id}/approve", tsId)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk();

        assertThat(entryApproved(unbilled)).isTrue();
        // INVOICED entry stays as it was — never re-approved.
        assertThat(entryApproved(invoiced)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Admin reject (reason) → REJECTED + member entries approved=false
    // -------------------------------------------------------------------------

    @Test
    void adminRejectsSubmittedTimesheetWithReason() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.SUBMITTED);
        UUID e1 = seedMemberEntry(contractorId, tsId);
        // Pre-set approved=true to prove reject flips it back.
        TimeEntry e = timeEntries.findByTenantIdAndId(tenantId, e1).block();
        e.setApproved(true);
        timeEntries.save(e).block();

        web.post().uri(uri -> uri.path("/timesheets/{id}/reject")
                        .queryParam("reason", "Hours look wrong").build(tsId))
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REJECTED")
                .jsonPath("$.note").isEqualTo("Hours look wrong");

        assertThat(entryApproved(e1)).isFalse();
    }

    @Test
    void adminRejectWithoutReasonIs4151() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.SUBMITTED);

        web.post().uri(uri -> uri.path("/timesheets/{id}/reject")
                        .queryParam("reason", "   ").build(tsId))
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4151);

        // Status unchanged.
        assertThat(timesheets.findByTenantIdAndId(tenantId, tsId).block().getStatus())
                .isEqualTo(Timesheet.Status.SUBMITTED);
    }

    // -------------------------------------------------------------------------
    // Contractor reopen REJECTED → OPEN
    // -------------------------------------------------------------------------

    @Test
    void contractorReopensRejectedTimesheet() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.REJECTED);
        UUID e1 = seedMemberEntry(contractorId, tsId);
        TimeEntry e = timeEntries.findByTenantIdAndId(tenantId, e1).block();
        e.setApproved(true); // stale true from a prior approve, say
        timeEntries.save(e).block();

        web.post().uri("/me/contractor/timesheets/{id}/reopen", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("OPEN");

        assertThat(entryApproved(e1)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Illegal transitions → 4150
    // -------------------------------------------------------------------------

    @Test
    void approveAnOpenTimesheetIs4150() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.OPEN);

        web.post().uri("/timesheets/{id}/approve", tsId)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4150);
    }

    @Test
    void submitAnApprovedTimesheetIs4150() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.APPROVED);

        web.post().uri("/me/contractor/timesheets/{id}/submit", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4150);
    }

    @Test
    void reopenAnOpenTimesheetIs4150() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.OPEN);

        web.post().uri("/me/contractor/timesheets/{id}/reopen", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4150);
    }

    // -------------------------------------------------------------------------
    // RBAC + ownership
    // -------------------------------------------------------------------------

    @Test
    void nonAdminApproveIs1800() {
        UUID tsId = seedTimesheet(contractorId, Timesheet.Status.SUBMITTED);

        web.post().uri("/timesheets/{id}/approve", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void contractorReachingAnotherContractorsTimesheetIsSame404With4133() {
        // A timesheet owned by otherContractor; the contractor must not be able to read it.
        UUID tsId = seedTimesheet(otherContractorId, Timesheet.Status.OPEN);

        web.get().uri("/me/contractor/timesheets/{id}", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4133);
    }

    @Test
    void contractorSubmittingAnotherContractorsTimesheetIsSame404With4133() {
        UUID tsId = seedTimesheet(otherContractorId, Timesheet.Status.OPEN);

        web.post().uri("/me/contractor/timesheets/{id}/submit", tsId)
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4133);

        // The other contractor's period is untouched.
        assertThat(timesheets.findByTenantIdAndId(tenantId, tsId).block().getStatus())
                .isEqualTo(Timesheet.Status.OPEN);
    }

    // -------------------------------------------------------------------------
    // Self list + admin review queue
    // -------------------------------------------------------------------------

    @Test
    void contractorListsOnlyOwnTimesheets() {
        seedTimesheet(contractorId, Timesheet.Status.OPEN);
        seedTimesheet(otherContractorId, Timesheet.Status.OPEN);

        web.get().uri("/me/contractor/timesheets")
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].userId").isEqualTo(contractorId.toString());
    }

    @Test
    void adminReviewQueueListsSubmittedAcrossUsers() {
        seedTimesheet(contractorId, Timesheet.Status.SUBMITTED, 0);
        seedTimesheet(otherContractorId, Timesheet.Status.SUBMITTED, 0);
        seedTimesheet(contractorId, Timesheet.Status.OPEN, 1); // excluded — not SUBMITTED

        web.get().uri(uri -> uri.path("/timesheets").queryParam("status", "SUBMITTED").build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }
}
