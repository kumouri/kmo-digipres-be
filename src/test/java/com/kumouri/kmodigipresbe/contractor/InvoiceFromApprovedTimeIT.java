package com.kumouri.kmodigipresbe.contractor;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.contractor.TimesheetRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — J3 approved-only invoicing gate. UNBILLED + billable + stopped time entries that
 * are NOT yet approved cannot be invoiced: {@code invoice-from-time} returns 4120 and creates
 * ZERO invoice. After the owning {@code Timesheet} is APPROVED (which flips the member
 * entries' {@code approved=true}) the same call succeeds — a DRAFT is created and the entries
 * are stamped INVOICED — and a re-invoke is idempotent (3520, no double-bill).
 *
 * <p>Mirrors the {@code InvoiceFromTimeIT} harness; the timesheet is approved through the real
 * ADMIN {@code POST /timesheets/{id}/approve} endpoint so the full gate path is exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class InvoiceFromApprovedTimeIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired TimesheetRepository timesheets;
    @Autowired TimeEntryRepository timeEntries;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID userId;
    private UUID projectId;
    private UUID timesheetId;
    private String adminToken;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ifat-it-" + tenantId)
                .displayName("IFAT IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User contractor = User.builder().id(userId).tenantId(tenantId).email("c@ifat.test")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build();
        users.save(contractor).block();

        // The approver is an ADMIN (also drives invoice-from-time, an admin-allowed action).
        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("a@ifat.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        projectId = UUID.randomUUID();
        projects.save(Project.builder().id(projectId).tenantId(tenantId).code("PRJ-IFAT-001")
                .name("Gated Invoicing Project").status(Project.ProjectStatus.ACTIVE).build()).block();

        // A SUBMITTED timesheet owning two UNBILLED, billable, stopped, NOT-approved entries.
        timesheetId = UUID.randomUUID();
        timesheets.save(Timesheet.builder().id(timesheetId).tenantId(tenantId).userId(userId)
                .periodStart(LocalDate.of(2026, 5, 18)).periodEnd(LocalDate.of(2026, 5, 24))
                .status(Timesheet.Status.SUBMITTED).build()).block();

        Instant s1 = Instant.parse("2026-05-19T09:00:00Z");
        timeEntries.save(TimeEntry.builder().id(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .projectId(projectId).timesheetId(timesheetId)
                .startedAt(s1).endedAt(s1.plusSeconds(3600)).durationSeconds(3600L)
                .billable(true).billingStatus(BillingStatus.UNBILLED).approved(false)
                .rateAmount(new BigDecimal("100.00")).description("Build").build()).block();

        Instant s2 = Instant.parse("2026-05-19T11:00:00Z");
        timeEntries.save(TimeEntry.builder().id(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .projectId(projectId).timesheetId(timesheetId)
                .startedAt(s2).endedAt(s2.plusSeconds(1800)).durationSeconds(1800L)
                .billable(true).billingStatus(BillingStatus.UNBILLED).approved(false)
                .rateAmount(new BigDecimal("100.00")).description("Review").build()).block();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), Timesheet.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private void invoiceFromTime(int expectedStatus, Integer expectedErrorCode) {
        var spec = web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"projectId":"%s","defaultRateAmount":100}
                        """.formatted(projectId))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
        if (expectedErrorCode != null) {
            spec.expectBody().jsonPath("$.errorCode").isEqualTo(expectedErrorCode);
        }
    }

    @Test
    void unapprovedTimeCannotBeInvoiced_4120_zeroInvoice() {
        // Entries exist (UNBILLED, billable, stopped) but NONE are approved → 4120, zero invoice.
        invoiceFromTime(409, 4120);
        assertThat(mongo.findAll(Invoice.class).count().block()).isEqualTo(0L);
        // Entries are untouched — still UNBILLED.
        assertThat(timeEntries.findAllByTenantIdAndTimesheetId(tenantId, timesheetId)
                .collectList().block())
                .allMatch(e -> e.getBillingStatus() == BillingStatus.UNBILLED);
    }

    @Test
    void afterTimesheetApproved_invoiceSucceeds_thenIdempotent3520() {
        // Pre-state: gated.
        invoiceFromTime(409, 4120);

        // Approve the owning timesheet → member entries flip approved=true.
        web.post().uri("/timesheets/{id}/approve", timesheetId)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");

        assertThat(timeEntries.findAllByTenantIdAndTimesheetId(tenantId, timesheetId)
                .collectList().block())
                .allMatch(TimeEntry::isApproved);

        // Now invoice-from-time succeeds: a DRAFT is created, entries stamped INVOICED.
        web.post().uri("/time-entries/invoice-from-time")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"projectId":"%s","defaultRateAmount":100}
                        """.formatted(projectId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.total").isEqualTo(150.00);

        List<Invoice> invoices = mongo.findAll(Invoice.class).collectList().block();
        assertThat(invoices).hasSize(1);
        assertThat(timeEntries.findAllByTenantIdAndTimesheetId(tenantId, timesheetId)
                .collectList().block())
                .allMatch(e -> e.getBillingStatus() == BillingStatus.INVOICED)
                .allMatch(e -> e.getInvoicedInvoiceId() != null);

        // Re-invoke → 3520 (all already invoiced → zero UNBILLED candidates), no double-bill.
        invoiceFromTime(409, 3520);
        assertThat(mongo.findAll(Invoice.class).count().block()).isEqualTo(1L);
    }
}
