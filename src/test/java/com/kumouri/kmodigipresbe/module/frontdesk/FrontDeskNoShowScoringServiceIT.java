package com.kumouri.kmodigipresbe.module.frontdesk;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.ai.LeadScoringJob;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.FrontDeskScoringJob;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.module.frontdesk.scoring.FrontDeskNoShowScoringService;
import com.kumouri.kmodigipresbe.repository.FrontDeskScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;
import reactor.core.Disposable;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrontDesk IQ (FD-1) integration tests for {@link FrontDeskNoShowScoringService} — the PHI-free parallel
 * fork of the chairfill no-show scorer that scores health {@link Appointment}s. Mirrors the
 * {@code NoShowRiskScoringIT} structure (drive via {@code runJobForTenant(...).block()}, no cron wait) plus
 * the {@code DomainEventPublisher.stream()} event-capture pattern.
 *
 * <p>Coverage (FD-1 plan §3.3 / hard gates):
 * <ol>
 *   <li>tenant with &ge; 40 terminal appointments trains a model ({@code source=MODEL});</li>
 *   <li>below-threshold tenant uses the deterministic rules fallback (never {@code MODEL}) — the cold-start
 *       path that carries a fresh practice night one (hard gate 3);</li>
 *   <li>cold-start ladder: long-lead-no-confirmation → HIGH; a true first-timer → INSUFFICIENT_DATA/LOW;</li>
 *   <li>scores are stable across consecutive runs (rules-path determinism);</li>
 *   <li>{@code APPOINTMENT_RISK_SCORED} fires per scored upcoming appointment with the right payload;</li>
 *   <li>only upcoming appointments are scored — terminal appointments are not re-stamped;</li>
 *   <li>a tenant without {@code frontdesk} in enabledModules is skipped by {@code nightlyRun} (blast
 *       radius);</li>
 *   <li><strong>the PHI fence (F1): the {@code Appointment} model exposes NO clinically-named field</strong>
 *       — a release-blocking reflection guard against future drift;</li>
 *   <li>(regression guard) the run does not touch lead-scoring.</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true"
})
class FrontDeskNoShowScoringServiceIT {

    @Autowired FrontDeskNoShowScoringService scoringService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired FrontDeskScoringJobRepository jobRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired DomainEventPublisher events;
    @Autowired ReactiveMongoTemplate mongo;

    @BeforeEach
    void cleanup() {
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), FrontDeskScoringJob.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), LeadScoringJob.class).block();
    }

    // ── 1. >=40 terminal appointments -> a trained model ──────────────────────────

    @Test
    void tenantWith40TerminalAppointments_usesModel() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));

        // 50 distinct contacts, two terminal appointments each so priors vary; both classes present.
        for (int i = 0; i < 50; i++) {
            UUID contactId = UUID.randomUUID();
            AppointmentStatus first = (i % 3 == 0) ? AppointmentStatus.NO_SHOW : AppointmentStatus.COMPLETED;
            AppointmentStatus second = (i % 5 == 0) ? AppointmentStatus.NO_SHOW : AppointmentStatus.COMPLETED;
            seedTerminal(tenantId, contactId, first,
                    Instant.now().minus(Duration.ofDays(120 + i)),
                    Instant.now().minus(Duration.ofDays(125 + i)));
            seedTerminal(tenantId, contactId, second,
                    Instant.now().minus(Duration.ofDays(60 + i)),
                    Instant.now().minus(Duration.ofDays(65 + i)));
        }

        List<UUID> upcoming = List.of(
                seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)),
                        VisitTypeBucket.RECALL, false, 1),
                seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(5)),
                        VisitTypeBucket.NEW_PATIENT, true, 0));

        runJob(tenantId);

        for (UUID id : upcoming) {
            Appointment a = load(id);
            assertThat(a).isNotNull();
            assertThat(a.getNoShowRisk()).isNotNull();
            assertThat(a.getNoShowRisk().source()).isEqualTo(NoShowRisk.SOURCE_MODEL);
            assertThat(a.getNoShowRisk().riskScore()).isBetween(0.0, 1.0);
            assertThat(a.getNoShowRisk().riskTier())
                    .isIn(NoShowRisk.TIER_LOW, NoShowRisk.TIER_MEDIUM, NoShowRisk.TIER_HIGH);
        }
    }

    // ── 2. below threshold -> rules fallback (cold start) ─────────────────────────

    @Test
    void tenantBelowThreshold_usesRulesFallback() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));

        // A flaky contact with a prior NO_SHOW (priorNoShowRate = 1.0 >= 0.34 -> HIGH on rules).
        UUID flakyContact = UUID.randomUUID();
        seedTerminal(tenantId, flakyContact, AppointmentStatus.NO_SHOW,
                Instant.now().minus(Duration.ofDays(30)),
                Instant.now().minus(Duration.ofDays(35)));

        UUID flakyUpcoming = seedUpcoming(tenantId, flakyContact, Instant.now().plus(Duration.ofDays(1)),
                VisitTypeBucket.FOLLOW_UP, false, 1);
        // A brand-new contact, short lead, already reminded -> LOW.
        UUID newContact = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofHours(8)),
                VisitTypeBucket.NEW_PATIENT, false, 1);

        runJob(tenantId);

        Appointment flaky = load(flakyUpcoming);
        assertThat(flaky).isNotNull();
        assertThat(flaky.getNoShowRisk().source())
                .isIn(NoShowRisk.SOURCE_RULES_FALLBACK, NoShowRisk.SOURCE_INSUFFICIENT_DATA);
        assertThat(flaky.getNoShowRisk().source()).isNotEqualTo(NoShowRisk.SOURCE_MODEL);
        assertThat(flaky.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_HIGH);

        Appointment newb = load(newContact);
        assertThat(newb).isNotNull();
        assertThat(newb.getNoShowRisk().source()).isNotEqualTo(NoShowRisk.SOURCE_MODEL);
        assertThat(newb.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_LOW);
    }

    // ── 3. cold-start ladder edges ────────────────────────────────────────────────

    @Test
    void coldStartLongLeadUnconfirmed_isHigh_andFirstTimerNoEvidence_isLow() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));

        // First-timer, long lead (20 days out), no reminder yet -> leadTimeHours > 336 && reminderCount==0 -> HIGH.
        UUID longLead = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(20)),
                VisitTypeBucket.NEW_PATIENT, false, 0);
        // First-timer, short lead, no history -> INSUFFICIENT_DATA -> LOW (never over-flagged).
        UUID firstTimer = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofHours(6)),
                VisitTypeBucket.NEW_PATIENT, false, 0);

        runJob(tenantId);

        assertThat(load(longLead).getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_HIGH);

        Appointment ft = load(firstTimer);
        assertThat(ft.getNoShowRisk().source()).isEqualTo(NoShowRisk.SOURCE_INSUFFICIENT_DATA);
        assertThat(ft.getNoShowRisk().riskTier()).isEqualTo(NoShowRisk.TIER_LOW);
    }

    // ── 4. determinism across runs (rules path) ───────────────────────────────────

    @Test
    void scoresStableAcrossRuns() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(20)),
                VisitTypeBucket.RECALL, false, 0);

        runJob(tenantId);
        double first = load(upcoming).getNoShowRisk().riskScore();
        runJob(tenantId);
        double second = load(upcoming).getNoShowRisk().riskScore();

        assertThat(second).isEqualTo(first);
    }

    // ── 5. APPOINTMENT_RISK_SCORED fires with the right payload ───────────────────

    @Test
    void emitsAppointmentRiskScored() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(3)),
                VisitTypeBucket.HYGIENE, false, 0);

        List<DomainEvent> captured = new CopyOnWriteArrayList<>();
        Disposable sub = events.stream()
                .filter(e -> DomainEventType.APPOINTMENT_RISK_SCORED.equals(e.type()))
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
        assertThat(e.payload()).containsKeys("appointmentId", "contactId", "providerId",
                "riskTier", "riskScore", "source");
        assertThat(e.payload().get("appointmentId")).isEqualTo(upcoming);
    }

    // ── 6. only upcoming scored; terminal untouched ───────────────────────────────

    @Test
    void onlyUpcomingScored_terminalUntouched() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));

        UUID completed = seedTerminal(tenantId, UUID.randomUUID(), AppointmentStatus.COMPLETED,
                Instant.now().minus(Duration.ofDays(10)), Instant.now().minus(Duration.ofDays(15)));
        UUID noShow = seedTerminal(tenantId, UUID.randomUUID(), AppointmentStatus.NO_SHOW,
                Instant.now().minus(Duration.ofDays(8)), Instant.now().minus(Duration.ofDays(12)));
        UUID cancelled = seedTerminal(tenantId, UUID.randomUUID(), AppointmentStatus.CANCELLED,
                Instant.now().minus(Duration.ofDays(6)), Instant.now().minus(Duration.ofDays(9)));
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)),
                VisitTypeBucket.RECALL, false, 0);

        runJob(tenantId);

        assertThat(load(completed).getNoShowRisk()).isNull();
        assertThat(load(noShow).getNoShowRisk()).isNull();
        assertThat(load(cancelled).getNoShowRisk()).isNull();
        assertThat(load(upcoming).getNoShowRisk()).isNotNull();
    }

    // ── 7. blast radius — a non-frontdesk tenant is skipped by nightlyRun ──────────

    @Test
    void moduleDisabled_skipsTenant() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa")); // no frontdesk
        UUID upcoming = seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)),
                VisitTypeBucket.RECALL, false, 0);

        scoringService.nightlyRun();
        sleep(500); // nightlyRun is fire-and-forget

        assertThat(load(upcoming).getNoShowRisk()).isNull();
        Long jobsForTenant = mongo.count(
                new Query(Criteria.where("tenantId").is(tenantId)),
                FrontDeskScoringJob.class).block();
        assertThat(jobsForTenant).isEqualTo(0L);
    }

    // ── 8. THE PHI FENCE (F1): no clinically-named field on Appointment ───────────

    /**
     * The marquee guard. The whole FrontDesk IQ pitch is that the model never touches the chart — enforced
     * by {@link Appointment} physically having no clinical field. This reflection test fails the build if a
     * future change adds a field whose name implies clinical content (diagnosis / procedure / chief complaint
     * / symptom / treatment / medication / clinical notes / chart / PHI). "Refuse the field, do not flag the
     * field" (FD-1 D4): that scope is the separately-priced BAA-gated compliance tier, not this build.
     */
    @Test
    void appointmentModelHasNoClinicalField() {
        String[] forbidden = {
                "diagnos", "procedure", "chiefcomplaint", "complaint", "symptom", "treatment",
                "medication", "prescription", "drug", "dosage", "clinical", "chart", "phi",
                "condition", "icd", "cpt", "labresult", "vital"
        };
        for (Field f : Appointment.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            for (String bad : forbidden) {
                assertThat(name.contains(bad))
                        .as("Appointment must carry NO clinical field (fence F1) — found a field named "
                                + "'" + f.getName() + "' matching forbidden token '" + bad + "'. That scope "
                                + "is the BAA-gated compliance tier, not FrontDesk IQ.")
                        .isFalse();
            }
        }
    }

    /**
     * Defense-in-depth for fence F1 at the feature layer: the scorer's feature vector is logistics-only and
     * fixed-width (9 features, FD-1 §3). A drift that added a clinical feature would change this length and
     * break the test, prompting review. Asserted directly off the package-private {@code features(...)}.
     */
    @Test
    void featureVectorIsLogisticsOnly_fixedWidth() {
        Appointment a = Appointment.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID()).contactId(UUID.randomUUID())
                .scheduledStart(Instant.now().plus(Duration.ofDays(3)))
                .createdAt(Instant.now())
                .visitTypeBucket(VisitTypeBucket.RECALL)
                .insuranceVerificationPending(true)
                .reminderCount(1)
                .build();
        double[] f = scoringService.features(a, List.of());
        assertThat(f).hasSize(9);
        // Every element is a finite number derived from logistics metadata (no NaN/Inf from a stray field).
        for (double v : f) {
            assertThat(Double.isFinite(v)).isTrue();
        }
    }

    // ── 9. regression: lead-scoring untouched ─────────────────────────────────────

    @Test
    void leadScoringUntouched() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));
        seedUpcoming(tenantId, UUID.randomUUID(), Instant.now().plus(Duration.ofDays(2)),
                VisitTypeBucket.RECALL, false, 0);

        runJob(tenantId);

        Criteria mine = Criteria.where("tenantId").is(tenantId);
        assertThat(mongo.count(new Query(mine), LeadScoringJob.class).block()).isEqualTo(0L);
        assertThat(mongo.count(new Query(mine), Contact.class).block()).isEqualTo(0L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Appointment load(UUID id) {
        return mongo.findById(id, Appointment.class).block();
    }

    private void runJob(UUID tenantId) {
        FrontDeskScoringJob job = FrontDeskScoringJob.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(FrontDeskScoringJob.JobStatus.PENDING).build();
        jobRepository.save(job).block();
        scoringService.runJobForTenant(tenantId, job).block();
    }

    private void seedTenant(UUID tenantId, Set<String> modules) {
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("frontdesk-" + tenantId)
                .displayName("FrontDesk IQ Test").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();
    }

    private UUID seedTerminal(UUID tenantId, UUID contactId, AppointmentStatus status,
                             Instant scheduledStart, Instant createdAt) {
        return seedAppointment(tenantId, contactId, status, scheduledStart, createdAt,
                VisitTypeBucket.RECALL, false, 0);
    }

    private UUID seedUpcoming(UUID tenantId, UUID contactId, Instant scheduledStart,
                             VisitTypeBucket bucket, boolean insurancePending, int reminderCount) {
        // createdAt = now (so leadTimeHours = scheduledStart - now).
        return seedAppointment(tenantId, contactId, AppointmentStatus.SCHEDULED,
                scheduledStart, Instant.now(), bucket, insurancePending, reminderCount);
    }

    private UUID seedAppointment(UUID tenantId, UUID contactId, AppointmentStatus status,
                                 Instant scheduledStart, Instant createdAt,
                                 VisitTypeBucket bucket, boolean insurancePending, int reminderCount) {
        Appointment a = Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .providerId(UUID.randomUUID())
                .scheduledStart(scheduledStart)
                .scheduledEnd(scheduledStart.plus(Duration.ofMinutes(30)))
                .status(status)
                .visitTypeBucket(bucket)
                .insuranceVerificationPending(insurancePending)
                .reminderCount(reminderCount)
                .build();
        UUID id = appointmentRepository.save(a).block().getId();
        // @CreatedDate overwrites a pre-set createdAt with "now" on insert, which would clamp leadTimeHours
        // to 0 for past-dated appointments. Patch it directly so the feature vector is honest.
        mongo.updateFirst(
                new Query(Criteria.where("_id").is(id)),
                Update.update("createdAt", createdAt),
                Appointment.class).block();
        return id;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
