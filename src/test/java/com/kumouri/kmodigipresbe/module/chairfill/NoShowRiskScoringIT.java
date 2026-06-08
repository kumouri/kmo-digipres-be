package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.ai.LeadScoringJob;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowScoringJob;
import com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.NoShowScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the ChairFill (CF-1) {@link NoShowRiskScoringService} — the parallel
 * fork of {@code LeadScoringV2Service} that scores salon {@link Booking}s. Mirrors the
 * {@code LeadScoringV2IT} structure (drive via {@code runJobForTenant(...).block()}, no cron wait)
 * plus the {@code WarrantyExpirationScanIT} event-capture pattern (subscribe to
 * {@code DomainEventPublisher.stream()} before triggering).
 *
 * <p>Coverage (plan §3.3):
 * <ol>
 *   <li>tenant with &ge; 40 terminal bookings trains a model ({@code source=MODEL})</li>
 *   <li>below-threshold tenant uses the deterministic rules fallback (never {@code MODEL})</li>
 *   <li>scores are stable across consecutive runs (rules-path determinism)</li>
 *   <li>{@code BOOKING_RISK_SCORED} fires per scored upcoming booking with the right payload</li>
 *   <li>only upcoming bookings are scored — terminal bookings are not re-stamped</li>
 *   <li>a tenant without {@code chairfill} in enabledModules is skipped by {@code nightlyRun}</li>
 *   <li>(regression guard) the run does not touch lead-scoring</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true"
})
class NoShowRiskScoringIT {

    @Autowired NoShowRiskScoringService scoringService;
    @Autowired BookingRepository bookingRepository;
    @Autowired ServiceMenuRepository serviceMenuRepository;
    @Autowired NoShowScoringJobRepository jobRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired DomainEventPublisher events;
    @Autowired ReactiveMongoTemplate mongo;

    private static final String MENU_ITEM_ID = "haircut";

    @BeforeEach
    void cleanup() {
        mongo.remove(new Query(), Booking.class).block();
        mongo.remove(new Query(), ServiceMenu.class).block();
        mongo.remove(new Query(), NoShowScoringJob.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), LeadScoringJob.class).block();
    }

    @Test
    void tenantWith40TerminalBookings_usesModel() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));

        // 50 terminal bookings across distinct contacts with varied histories, both classes present.
        for (int i = 0; i < 50; i++) {
            UUID contactId = UUID.randomUUID();
            // Give each contact two terminal bookings so priors vary; every 3rd is a no-show.
            BookingStatus first = (i % 3 == 0) ? BookingStatus.NO_SHOW : BookingStatus.COMPLETED;
            BookingStatus second = (i % 5 == 0) ? BookingStatus.NO_SHOW : BookingStatus.COMPLETED;
            seedBooking(tenantId, contactId, first,
                    Instant.now().minus(Duration.ofDays(120 + i)),
                    Instant.now().minus(Duration.ofDays(125 + i)), false, false);
            seedBooking(tenantId, contactId, second,
                    Instant.now().minus(Duration.ofDays(60 + i)),
                    Instant.now().minus(Duration.ofDays(65 + i)), false, false);
        }

        // A few upcoming bookings to be scored.
        List<UUID> upcoming = List.of(
                seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)), false, false),
                seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(5)), true, true));

        runJob(tenantId);

        for (UUID id : upcoming) {
            Booking b = loadBooking(id);
            assertThat(b).isNotNull();
            assertThat(b.getNoShowRisk()).isNotNull();
            assertThat(b.getNoShowRisk().source()).isEqualTo(NoShowRisk.SOURCE_MODEL);
            assertThat(b.getNoShowRisk().riskScore()).isBetween(0.0, 1.0);
            assertThat(b.getNoShowRisk().riskTier())
                    .isIn(NoShowRisk.TIER_LOW, NoShowRisk.TIER_MEDIUM, NoShowRisk.TIER_HIGH);
        }
    }

    @Test
    void tenantBelowThreshold_usesRulesFallback() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));

        // Only a handful of terminal bookings -> below MIN_BOOKINGS_FOR_MODEL (40) -> rules.
        UUID flakyContact = UUID.randomUUID();
        // A prior NO_SHOW for the flaky contact (priorNoShowRate = 1.0 >= 0.34 -> HIGH on rules).
        seedBooking(tenantId, flakyContact, BookingStatus.NO_SHOW,
                Instant.now().minus(Duration.ofDays(30)),
                Instant.now().minus(Duration.ofDays(35)), false, false);

        // HIGH via prior-no-show-rate: an upcoming booking for the flaky contact, short lead time + no deposit.
        UUID flakyUpcoming = seedUpcoming(tenantId, flakyContact, Instant.now().plus(Duration.ofDays(1)), false, false);
        // LOW: a brand-new contact WITH a paid deposit (positive evidence) on a short-lead booking.
        UUID newWithDeposit = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(1)), true, true);

        runJob(tenantId);

        Booking flaky = loadBooking(flakyUpcoming);
        assertThat(flaky).isNotNull();
        assertThat(flaky.getNoShowRisk().source())
                .isIn(NoShowRisk.SOURCE_RULES_FALLBACK, NoShowRisk.SOURCE_INSUFFICIENT_DATA);
        assertThat(flaky.getNoShowRisk().source()).isNotEqualTo(NoShowRisk.SOURCE_MODEL);
        assertThat(flaky.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_HIGH);

        Booking newb = loadBooking(newWithDeposit);
        assertThat(newb).isNotNull();
        assertThat(newb.getNoShowRisk().source()).isNotEqualTo(NoShowRisk.SOURCE_MODEL);
        assertThat(newb.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_LOW);
    }

    @Test
    void coldStartLongLeadNoDeposit_isHigh_andFirstTimerNoEvidence_isLow() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));

        // First-timer, long lead (20 days out), no deposit -> leadTimeHours > 336 && no deposit -> HIGH.
        UUID longLead = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(20)), false, false);
        // First-timer, short lead, no deposit, no history -> INSUFFICIENT_DATA -> LOW (never punished).
        UUID firstTimer = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofHours(6)), false, false);

        runJob(tenantId);

        Booking ll = loadBooking(longLead);
        assertThat(ll).isNotNull();
        assertThat(ll.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_HIGH);

        Booking ft = loadBooking(firstTimer);
        assertThat(ft).isNotNull();
        assertThat(ft.getNoShowRisk().source()).isEqualTo(NoShowRisk.SOURCE_INSUFFICIENT_DATA);
        assertThat(ft.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_LOW);
    }

    @Test
    void scoresStableAcrossRuns() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(20)), false, false);

        runJob(tenantId);
        double first = loadBooking(upcoming).getNoShowRisk().riskScore();

        runJob(tenantId);
        double second = loadBooking(upcoming).getNoShowRisk().riskScore();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void emitsBookingRiskScored() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(3)), false, false);

        List<DomainEvent> captured = new CopyOnWriteArrayList<>();
        Disposable sub = events.stream()
                .filter(e -> DomainEventType.BOOKING_RISK_SCORED.equals(e.type()))
                .subscribe(captured::add);
        try {
            runJob(tenantId);
            Thread.sleep(300);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            sub.dispose();
        }

        assertThat(captured).hasSize(1);
        DomainEvent e = captured.get(0);
        assertThat(e.tenantId()).isEqualTo(tenantId);
        assertThat(e.subjectId()).isEqualTo(upcoming);
        assertThat(e.payload()).containsKeys("bookingId", "contactId", "staffMemberId",
                "riskTier", "riskScore", "source");
        assertThat(e.payload().get("bookingId")).isEqualTo(upcoming);
    }

    @Test
    void onlyUpcomingScored_terminalUntouched() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));

        UUID completed = seedBooking(tenantId, UUID.randomUUID(), BookingStatus.COMPLETED,
                Instant.now().minus(Duration.ofDays(10)), Instant.now().minus(Duration.ofDays(15)), false, false);
        UUID noShow = seedBooking(tenantId, UUID.randomUUID(), BookingStatus.NO_SHOW,
                Instant.now().minus(Duration.ofDays(8)), Instant.now().minus(Duration.ofDays(12)), false, false);
        UUID cancelled = seedBooking(tenantId, UUID.randomUUID(), BookingStatus.CANCELLED,
                Instant.now().minus(Duration.ofDays(6)), Instant.now().minus(Duration.ofDays(9)), false, false);
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)), false, false);

        runJob(tenantId);

        assertThat(loadBooking(completed).getNoShowRisk()).isNull();
        assertThat(loadBooking(noShow).getNoShowRisk()).isNull();
        assertThat(loadBooking(cancelled).getNoShowRisk()).isNull();
        assertThat(loadBooking(upcoming).getNoShowRisk()).isNotNull();
    }

    @Test
    void moduleDisabled_skipsTenant() {
        // Tenant WITHOUT "chairfill" in enabledModules -> nightlyRun must skip it.
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa")); // no chairfill
        seedMenu(tenantId, new BigDecimal("60.00"));
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)), false, false);

        scoringService.nightlyRun();
        sleep(500); // nightlyRun is fire-and-forget

        // No stamp + no job FOR THIS TENANT (a concurrent cached context may seed other tenants
        // against the shared Mongo, so we scope the job check to this tenantId rather than a global
        // count — the booking-not-stamped assertion is the real blast-radius proof).
        assertThat(loadBooking(upcoming).getNoShowRisk()).isNull();
        Long jobsForTenant = mongo.count(
                new Query(org.springframework.data.mongodb.core.query.Criteria.where("tenantId").is(tenantId)),
                NoShowScoringJob.class).block();
        assertThat(jobsForTenant).isEqualTo(0L);
    }

    @Test
    void leadScoringUntouched() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedMenu(tenantId, new BigDecimal("60.00"));
        seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)), false, false);

        runJob(tenantId);

        // The no-show run must not create any LeadScoringJob or write Contact.leadScore.
        // Scoped to this tenant so a concurrent cached context's lead-scoring can't perturb the count.
        org.springframework.data.mongodb.core.query.Criteria mine =
                org.springframework.data.mongodb.core.query.Criteria.where("tenantId").is(tenantId);
        assertThat(mongo.count(new Query(mine), LeadScoringJob.class).block()).isEqualTo(0L);
        assertThat(mongo.count(new Query(mine), Contact.class).block()).isEqualTo(0L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Context-free read of a booking by id. {@code BookingRepository.findById} applies the
     * auto-tenant-filter and so {@code required()}s a request TenantContext (errorCode 1001) the
     * test does not have; the nightly job reads via the explicit-param {@code findAllByTenantId}.
     * Assertions use the template directly, mirroring the IT's {@code mongo.remove} cleanup style.
     */
    private Booking loadBooking(UUID id) {
        return mongo.findById(id, Booking.class).block();
    }

    private void runJob(UUID tenantId) {
        NoShowScoringJob job = NoShowScoringJob.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(NoShowScoringJob.JobStatus.PENDING).build();
        jobRepository.save(job).block();
        scoringService.runJobForTenant(tenantId, job).block();
    }

    private void seedTenant(UUID tenantId, Set<String> modules) {
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("chairfill-" + tenantId)
                .displayName("ChairFill Test").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();
    }

    private void seedMenu(UUID tenantId, BigDecimal price) {
        ServiceMenu menu = ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Main Menu")
                .services(List.of(ServiceMenuItem.builder()
                        .id(MENU_ITEM_ID).name("Haircut").durationMinutes(45).price(price).build()))
                .build();
        serviceMenuRepository.save(menu).block();
    }

    private UUID seedBooking(UUID tenantId, UUID contactId, BookingStatus status,
                             Instant scheduledStart, Instant createdAt,
                             boolean depositRequired, boolean depositPaid) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .staffMemberId(UUID.randomUUID())
                .serviceMenuItemId(MENU_ITEM_ID)
                .serviceMenuItemName("Haircut")
                .scheduledStart(scheduledStart)
                .scheduledEnd(scheduledStart.plus(Duration.ofMinutes(45)))
                .status(status)
                .depositRequired(depositRequired)
                .depositPaid(depositPaid)
                .build();
        UUID id = bookingRepository.save(b).block().getId();
        // @CreatedDate (auditing) overwrites a pre-set createdAt with "now" on insert, which would
        // clamp leadTimeHours to 0 for past-dated bookings. Patch it directly so the feature vector
        // (leadTimeHours = scheduledStart - createdAt) is honest for both training + scoring.
        mongo.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                org.springframework.data.mongodb.core.query.Update.update("createdAt", createdAt),
                Booking.class).block();
        return id;
    }

    private UUID seedUpcoming(UUID tenantId, UUID contactId, Instant scheduledStart,
                             boolean depositRequired, boolean depositPaid) {
        // createdAt = now (so leadTimeHours = scheduledStart - now).
        return seedBooking(tenantId, contactId, BookingStatus.CONFIRMED,
                scheduledStart, Instant.now(), depositRequired, depositPaid);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
