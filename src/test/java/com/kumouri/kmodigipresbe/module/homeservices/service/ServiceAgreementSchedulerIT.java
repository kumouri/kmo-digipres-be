package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scheduler-materialization + idempotency for {@link ServiceAgreementSchedulerService}.
 *
 * <h2>Pre-existing date-flake — fixed-Clock determinism (NOT a Phase-E change)</h2>
 * {@code weeklyAgreement_...} and {@code badRrule_...} intermittently failed
 * {@code expected 4, was 3}: they seeded {@code startDate = LocalDate.now()+1d}
 * with {@code FREQ=WEEKLY;COUNT=4} while the autowired scheduler read
 * {@code Clock.systemUTC()}. On certain real calendar weekdays ical4j's WEEKLY
 * expansion anchors the first occurrence on/before the real {@code now}, so the
 * scheduler's {@code [now, now+90d]} window filter drops it → only 3 of 4
 * materialize. This is a Phase-D/home-services flake with <strong>zero Phase-E
 * linkage</strong> ({@code git diff origin/main..HEAD -- .../module/} is empty;
 * {@code ServiceAgreement}/{@code MaintenanceVisit} live only under {@code module/}
 * and Phase E never touched it). Fix mirrors the in-repo
 * {@code ServiceAgreementSchedulerInvalidRruleTest} precedent: a fixed
 * {@code @Primary Clock} ({@code HomeServicesAutoConfiguration} builds the scheduler
 * via {@code ObjectProvider<Clock>.getIfAvailable(systemUTC)}, so this bean is what
 * it uses) plus all date seeds derived from that SAME fixed instant. The test
 * intent (ACTIVE materializes its occurrences; rerun idempotent via the unique
 * index; PAUSED skipped; bad-RRULE skipped while good still materializes) is
 * preserved with the original exact assertions. Do not revert to
 * {@code LocalDate.now()}.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ServiceAgreementSchedulerIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.files.region=us-east-1",
        // Disable the auto-tick so we control when materialization runs.
        "kmosf.home-services.scheduler.initial-delay-ms=86400000"
})
class ServiceAgreementSchedulerIT {

    /**
     * A fixed instant — a Thursday noon. {@code startDate = today+1d = Fri May 15};
     * {@code FREQ=WEEKLY;COUNT=4} from a Friday seed → May 15 / 22 / 29 / Jun 5,
     * all strictly after {@code now} and inside the scheduler's
     * {@code [now, now+90d]} window on this fixed date (kills the weekday/window
     * coupling that caused the pre-existing flake).
     */
    static final Instant FIXED_NOW = Instant.parse("2026-05-14T12:00:00Z");
    static final LocalDate FIXED_TODAY = LocalDate.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired ServiceAgreementSchedulerService scheduler;
    @Autowired ServiceAgreementRepository agreements;
    @Autowired MaintenanceVisitRepository visits;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), ServiceAgreement.class).block();
        mongo.remove(new Query(), MaintenanceVisit.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void weeklyAgreement_materializesFourVisits_andRerunIsIdempotent() {
        // FREQ=WEEKLY;COUNT=4 starting tomorrow produces 4 occurrences within
        // the scheduler's 90-day window. Quarterly RRULEs only fit one
        // occurrence per window — they materialize across multiple ticks
        // over the year as the window slides forward.
        ServiceAgreement seeded = agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("weekly-bait-station")
                        // Seed (fixed-clock relative) strictly after now so all 4
                        // weekly occurrences fall in [now, now+90d] — RRULE expansion
                        // treats DTSTART as the first occurrence and filters
                        // past-the-window matches.
                        .startDate(FIXED_TODAY.plusDays(1))
                        .recurrenceRule("FREQ=WEEKLY;COUNT=4")
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(seeded).isNotNull();

        // First tick: 4 visits materialize.
        scheduler.runDueOnce().block();
        List<MaintenanceVisit> after1 = visits
                .findAllByTenantIdAndServiceAgreementId(tenantId, seeded.getId())
                .collectList()
                .block();
        assertThat(after1).hasSize(4);
        assertThat(after1).allSatisfy(v -> {
            assertThat(v.getTenantId()).isEqualTo(tenantId);
            assertThat(v.getServiceAgreementId()).isEqualTo(seeded.getId());
            assertThat(v.getScheduledStart()).isNotNull();
        });

        // Second tick: unique index dedupes; still 4 visits, same IDs.
        scheduler.runDueOnce().block();
        List<MaintenanceVisit> after2 = visits
                .findAllByTenantIdAndServiceAgreementId(tenantId, seeded.getId())
                .collectList()
                .block();
        assertThat(after2).hasSize(4);
        assertThat(after2.stream().map(MaintenanceVisit::getId).toList())
                .containsExactlyInAnyOrderElementsOf(
                        after1.stream().map(MaintenanceVisit::getId).toList());
    }

    @Test
    void nonActiveAgreement_isSkipped() {
        agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("paused-hvac")
                        // Seed (fixed-clock relative) strictly after now; PAUSED ⇒
                        // nothing materializes anyway, but kept consistent with the
                        // fixed-clock determinism rationale (class Javadoc).
                        .startDate(FIXED_TODAY.plusDays(1))
                        .recurrenceRule("FREQ=WEEKLY;COUNT=4")
                        .status(ServiceAgreementStatus.PAUSED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        scheduler.runDueOnce().block();

        List<MaintenanceVisit> after = visits.findAllByTenantIdAndStatus(
                        tenantId, com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus.SCHEDULED)
                .collectList()
                .block();
        assertThat(after).isEmpty();
    }

    @Test
    void badRrule_isLoggedAndSkipped_otherAgreementsStillMaterialize() {
        // Bad-RRULE agreement.
        agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("bad-rrule")
                        .startDate(FIXED_TODAY.minusDays(30))
                        .recurrenceRule("THIS-IS-NOT-AN-RRULE")
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        // Good-RRULE agreement, same tenant — weekly so all 4 occurrences
        // fit inside the 90-day scheduler window.
        ServiceAgreement good = agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("weekly-bait-station")
                        // Seed (fixed-clock relative) strictly after now so all 4
                        // weekly occurrences fall in [now, now+90d] — RRULE expansion
                        // treats DTSTART as the first occurrence and filters
                        // past-the-window matches.
                        .startDate(FIXED_TODAY.plusDays(1))
                        .recurrenceRule("FREQ=WEEKLY;COUNT=4")
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        scheduler.runDueOnce().block();

        List<MaintenanceVisit> goodVisits = visits
                .findAllByTenantIdAndServiceAgreementId(tenantId, good.getId())
                .collectList()
                .block();
        assertThat(goodVisits).hasSize(4);
    }
}
