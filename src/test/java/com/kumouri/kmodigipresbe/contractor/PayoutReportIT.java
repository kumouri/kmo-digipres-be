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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — J4 payout + margin report (the 1099 payout view). An ADMIN sees what is owed a
 * contractor (payout = approved hours × cost rate) plus margin (bill − payout), per
 * {@code Timesheet} period and year-to-date. Only APPROVED time counts (the J3 invoicing
 * gate); an approved entry with a null cost rate is surfaced via {@code hasUnratedEntries}
 * (its hours still count toward the total) and never silently zeroed into the money.
 *
 * <p>Mirrors the {@code TimesheetLifecycleIT}/{@code ContractorScopeIT} harness:
 * @SpringBootTest RANDOM_PORT + WebTestClient + Testcontainers Mongo + the
 * quartz-proof-job-off property; seed a Tenant + ADMIN + CONTRACTOR (+ a second tenant for
 * isolation) + Timesheets with member TimeEntries of known durations/rates; JWTs via
 * JwtTokenService. All assertions go through the admin token unless a scenario says otherwise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class PayoutReportIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired TimesheetRepository timesheets;
    @Autowired TimeEntryRepository timeEntries;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private static final BigDecimal COST = new BigDecimal("50.00");
    private static final BigDecimal BILL = new BigDecimal("120.00");

    private UUID tenantId;
    private UUID contractorId;

    private String contractorToken;
    private String adminToken;
    private String staffToken;

    // Second tenant — its time must never leak into tenant A's report.
    private UUID otherTenantId;
    private UUID otherUserId;
    private String otherAdminToken;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("pay-it-" + tenantId)
                .displayName("Pay IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        contractorId = UUID.randomUUID();
        User contractor = User.builder().id(contractorId).tenantId(tenantId).email("c@pay.test")
                .displayName("Casey Contractor")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build();
        users.save(contractor).block();
        contractorToken = "Bearer " + jwt.mint(contractor);

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("a@pay.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("s@pay.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        // Second tenant + its admin + contractor — the isolation control.
        otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(otherTenantId).slug("pay-it2-" + otherTenantId)
                .displayName("Pay IT 2").status(Tenant.TenantStatus.ACTIVE).build()).block();
        otherUserId = UUID.randomUUID();
        User otherContractor = User.builder().id(otherUserId).tenantId(otherTenantId)
                .email("c@pay2.test").displayName("Other Contractor")
                .roles(Set.of("STAFF", "CONTRACTOR")).status(User.UserStatus.ACTIVE).build();
        users.save(otherContractor).block();
        User otherAdmin = User.builder().id(UUID.randomUUID()).tenantId(otherTenantId)
                .email("a@pay2.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(otherAdmin).block();
        otherAdminToken = "Bearer " + jwt.mint(otherAdmin);
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
    // Seeding helpers
    // -------------------------------------------------------------------------

    /** A per-(user, week) period starting {@code start} (Monday). */
    private UUID seedTimesheet(UUID tenant, UUID userId, LocalDate start) {
        UUID id = UUID.randomUUID();
        timesheets.save(Timesheet.builder().id(id).tenantId(tenant).userId(userId)
                .periodStart(start).periodEnd(start.plusDays(6))
                .status(Timesheet.Status.APPROVED).build()).block();
        return id;
    }

    /**
     * A member time entry. {@code hours} drives durationSeconds; {@code cost}/{@code bill}
     * may be null to model an unrated entry. {@code timesheetId} may be null (ungrouped).
     */
    private UUID seedEntry(UUID tenant, UUID userId, UUID timesheetId, Instant startedAt,
                           long hours, boolean approved, BigDecimal cost, BigDecimal bill) {
        UUID id = UUID.randomUUID();
        timeEntries.save(TimeEntry.builder().id(id).tenantId(tenant).userId(userId)
                .timesheetId(timesheetId).startedAt(startedAt)
                .endedAt(startedAt.plusSeconds(hours * 3600)).durationSeconds(hours * 3600)
                .billable(true).billingStatus(TimeEntry.BillingStatus.UNBILLED)
                .approved(approved).costRateAmount(cost).rateAmount(bill).build()).block();
        return id;
    }

    // -------------------------------------------------------------------------
    // The headline rollup: approved-only money, hours include unrated, margin, periods
    // -------------------------------------------------------------------------

    @Test
    void payoutCountsApprovedOnlyAndSurfacesUnratedAndComputesMarginAndPeriods() {
        LocalDate weekA = LocalDate.of(2026, 5, 18);
        LocalDate weekB = LocalDate.of(2026, 5, 25);
        UUID tsA = seedTimesheet(tenantId, contractorId, weekA);
        UUID tsB = seedTimesheet(tenantId, contractorId, weekB);

        Instant inA = Instant.parse("2026-05-19T09:00:00Z");
        Instant inB = Instant.parse("2026-05-26T09:00:00Z");

        // Period A: two approved 1h entries (cost 50 / bill 120 each).
        seedEntry(tenantId, contractorId, tsA, inA, 1, true, COST, BILL);
        seedEntry(tenantId, contractorId, tsA, inA.plusSeconds(7200), 1, true, COST, BILL);
        // Period A: one UNAPPROVED 5h entry — excluded from everything.
        seedEntry(tenantId, contractorId, tsA, inA.plusSeconds(20000), 5, false, COST, BILL);
        // Period A: one APPROVED 1h entry with a NULL cost rate (bill still 120) — its hours
        // count, it sets hasUnratedEntries, it adds nothing to payout but 120 to bill.
        seedEntry(tenantId, contractorId, tsA, inA.plusSeconds(30000), 1, true, null, BILL);

        // Period B: one approved 2h entry (cost 50 / bill 120).
        seedEntry(tenantId, contractorId, tsB, inB, 2, true, COST, BILL);

        // Window covers both weeks.
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z");

        // Totals: hours = 1+1+1(unrated)+2 = 5; payout = 50+50+0+100 = 200;
        // bill = 120+120+120+240 = 600; margin = 400; hasUnratedEntries = true.
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", contractorId)
                        .queryParam("from", from)
                        .queryParam("to", to).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(contractorId.toString())
                .jsonPath("$.displayName").isEqualTo("Casey Contractor")
                .jsonPath("$.totalHours").isEqualTo(5.0)
                .jsonPath("$.payout").isEqualTo(200.0)
                .jsonPath("$.bill").isEqualTo(600.0)
                .jsonPath("$.margin").isEqualTo(400.0)
                .jsonPath("$.hasUnratedEntries").isEqualTo(true)
                // Two period lines, sorted by periodStart ascending.
                .jsonPath("$.periods.length()").isEqualTo(2)
                .jsonPath("$.periods[0].periodStart").isEqualTo("2026-05-18")
                .jsonPath("$.periods[0].hours").isEqualTo(3.0)      // 1+1+1(unrated)
                .jsonPath("$.periods[0].payout").isEqualTo(100.0)   // 50+50, unrated adds 0
                .jsonPath("$.periods[0].bill").isEqualTo(360.0)     // 120×3
                .jsonPath("$.periods[0].margin").isEqualTo(260.0)
                .jsonPath("$.periods[1].periodStart").isEqualTo("2026-05-25")
                .jsonPath("$.periods[1].hours").isEqualTo(2.0)
                .jsonPath("$.periods[1].payout").isEqualTo(100.0)
                .jsonPath("$.periods[1].bill").isEqualTo(240.0)
                .jsonPath("$.periods[1].margin").isEqualTo(140.0);
    }

    @Test
    void entriesWithNoTimesheetFallIntoAnUngroupedNullPeriodLine() {
        // An approved entry with a null timesheetId groups into the ungrouped bucket.
        Instant t = Instant.parse("2026-05-19T09:00:00Z");
        seedEntry(tenantId, contractorId, null, t, 1, true, COST, BILL);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z");

        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", contractorId)
                        .queryParam("from", from)
                        .queryParam("to", to).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalHours").isEqualTo(1.0)
                .jsonPath("$.payout").isEqualTo(50.0)
                .jsonPath("$.periods.length()").isEqualTo(1)
                // Ungrouped line carries null period bounds.
                .jsonPath("$.periods[0].periodStart").doesNotExist()
                .jsonPath("$.periods[0].periodEnd").doesNotExist()
                .jsonPath("$.periods[0].hours").isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // YTD window
    // -------------------------------------------------------------------------

    @Test
    void ytdPicksUpInYearEntriesAndExcludesOutOfWindow() {
        // Current year is 2026 (test clock). YTD 2026 window = Jan 1 2026 → now.
        UUID ts2026 = seedTimesheet(tenantId, contractorId, LocalDate.of(2026, 3, 2));
        // In-year approved 4h entry — counts.
        seedEntry(tenantId, contractorId, ts2026, Instant.parse("2026-03-03T09:00:00Z"),
                4, true, COST, BILL);
        // Prior-year (2025) approved entry — before the YTD window, excluded.
        UUID ts2025 = seedTimesheet(tenantId, contractorId, LocalDate.of(2025, 3, 3));
        seedEntry(tenantId, contractorId, ts2025, Instant.parse("2025-03-04T09:00:00Z"),
                9, true, COST, BILL);

        web.get().uri(uri -> uri.path("/reports/payout/ytd")
                        .queryParam("userId", contractorId)
                        .queryParam("year", 2026).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // Only the 4h 2026 entry: payout 200, bill 480.
                .jsonPath("$.totalHours").isEqualTo(4.0)
                .jsonPath("$.payout").isEqualTo(200.0)
                .jsonPath("$.bill").isEqualTo(480.0)
                .jsonPath("$.hasUnratedEntries").isEqualTo(false);
    }

    // -------------------------------------------------------------------------
    // Cross-tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void anotherTenantsTimeNeverAppears() {
        // Tenant A: one approved 1h entry.
        UUID tsA = seedTimesheet(tenantId, contractorId, LocalDate.of(2026, 5, 18));
        seedEntry(tenantId, contractorId, tsA, Instant.parse("2026-05-19T09:00:00Z"),
                1, true, COST, BILL);

        // Tenant B: a big approved entry for ITS contractor — must not leak into A.
        UUID tsB = seedTimesheet(otherTenantId, otherUserId, LocalDate.of(2026, 5, 18));
        seedEntry(otherTenantId, otherUserId, tsB, Instant.parse("2026-05-19T09:00:00Z"),
                100, true, COST, BILL);

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z");

        // Tenant A admin asks for tenant A's contractor → sees only the 1h entry.
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", contractorId)
                        .queryParam("from", from)
                        .queryParam("to", to).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalHours").isEqualTo(1.0)
                .jsonPath("$.payout").isEqualTo(50.0);

        // Tenant A admin asking for tenant B's userId sees nothing (the entry is B-scoped;
        // the finder is tenant-scoped to A) — zero hours, empty periods.
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", otherUserId)
                        .queryParam("from", from)
                        .queryParam("to", to).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalHours").isEqualTo(0.0)
                .jsonPath("$.payout").isEqualTo(0.0)
                .jsonPath("$.periods.length()").isEqualTo(0);

        // And tenant B's own admin sees B's 100h — proving the entry exists, just isolated.
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", otherUserId)
                        .queryParam("from", from)
                        .queryParam("to", to).build())
                .header("Authorization", otherAdminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalHours").isEqualTo(100.0)
                .jsonPath("$.payout").isEqualTo(5000.0);
    }

    // -------------------------------------------------------------------------
    // RBAC + request validation
    // -------------------------------------------------------------------------

    @Test
    void nonAdminStaffIs1800() {
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", contractorId)
                        .queryParam("from", Instant.parse("2026-05-01T00:00:00Z"))
                        .queryParam("to", Instant.parse("2026-06-01T00:00:00Z")).build())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void contractorTokenIs1800() {
        // A CONTRACTOR is STAFF but not ADMIN — the report is an admin payout view.
        web.get().uri(uri -> uri.path("/reports/payout/ytd")
                        .queryParam("userId", contractorId)
                        .queryParam("year", 2026).build())
                .header("Authorization", contractorToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void missingUserIdIs4160() {
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("from", Instant.parse("2026-05-01T00:00:00Z"))
                        .queryParam("to", Instant.parse("2026-06-01T00:00:00Z")).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4160);
    }

    @Test
    void toNotAfterFromIs4160() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-01T00:00:00Z"); // before from
        web.get().uri(uri -> uri.path("/reports/payout")
                        .queryParam("userId", contractorId)
                        .queryParam("from", from)
                        .queryParam("to", to).build())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4160);
    }
}
