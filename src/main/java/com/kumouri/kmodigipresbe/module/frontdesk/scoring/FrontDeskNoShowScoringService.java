package com.kumouri.kmodigipresbe.module.frontdesk.scoring;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.FrontDeskScoringJob;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.FrontDeskScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import smile.classification.LogisticRegression;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FrontDesk IQ (FD-1) — nightly, per-tenant, <strong>PHI-free</strong> no-show-risk scoring for health
 * {@link Appointment}s.
 *
 * <p><strong>A parallel fork of the chairfill {@code NoShowRiskScoringService} (FD-1 D2), which is itself a
 * parallel fork of {@code LeadScoringV2Service} — NOT a generalization of either.</strong> Same machine —
 * nightly {@link Scheduled} per-tenant, a {@code >= MIN_APPTS_FOR_MODEL}-sample model-vs-rules gate, a
 * Smile {@link LogisticRegression}, a {@link FrontDeskScoringJob} ledger,
 * {@link Schedulers#boundedElastic()} for the blocking ML work, and a domain-event emit — but pointed at
 * {@link Appointment}s instead of salon {@code Booking}s. Forking one level out keeps CF-1's scorer (and
 * the NMM-relied-upon lead-scorer) regression-proof: <strong>FrontDesk reads only
 * {@link AppointmentRepository}, never {@code BookingRepository}</strong>, so the two scorers share the
 * {@code NoShowRisk} value <em>type</em> but never share data.
 *
 * <h2>PHI-free by construction (fence F1)</h2>
 * <p>The feature vector ({@link #features}) is built <strong>only</strong> from {@link Appointment}
 * logistics metadata — there is no {@code ServiceMenu}/price lookup (health has no price band) and, more
 * importantly, <strong>no clinical field exists on {@link Appointment} for a feature to read.</strong> The
 * model literally cannot see a diagnosis, procedure, or chief complaint. {@code visitTypeBucket} is the
 * closest the vector rides to the line, and it is a closed logistics enum consumed only as an ordinal — see
 * {@link com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket}. A release-blocking IT asserts
 * via reflection that {@link Appointment} carries no clinically-named field, so the boundary cannot drift
 * open (FrontDesk IQ plan §0).
 *
 * <p><strong>Label polarity (mirror CF-1):</strong> train on <em>terminal</em> appointments —
 * {@code NO_SHOW = 1} / {@code COMPLETED = 0} (CANCELLED excluded — a cancel is not a no-show), so the
 * predicted score is P(no-show).
 *
 * <p>Wired as a {@code @Bean} in {@link FrontDeskAutoConfiguration} (the RealEstate/ChairFill
 * hand-construction pattern), so it exists only when {@code kmosf.modules.frontdesk.enabled=true}. The
 * nightly run additionally filters to tenants whose {@code enabledModules} contains {@code "frontdesk"}, so
 * every non-frontdesk tenant is skipped: blast radius zero.
 */
@Slf4j
public class FrontDeskNoShowScoringService {

    /**
     * Minimum terminal (COMPLETED/NO_SHOW) appointments before a model is trained. CF-1's value (40);
     * below it the deterministic rules fallback carries the night-one path so a fresh practice gets value
     * immediately (FD-1 hard gate 3).
     */
    static final int MIN_APPTS_FOR_MODEL = 40;

    private final TenantRepository tenantRepository;
    private final AppointmentRepository appointmentRepository;
    private final FrontDeskScoringJobRepository jobRepository;
    private final DomainEventPublisher eventPublisher;

    private final double highThreshold;
    private final double mediumThreshold;

    public FrontDeskNoShowScoringService(TenantRepository tenantRepository,
                                         AppointmentRepository appointmentRepository,
                                         FrontDeskScoringJobRepository jobRepository,
                                         DomainEventPublisher eventPublisher,
                                         double highThreshold,
                                         double mediumThreshold) {
        this.tenantRepository = tenantRepository;
        this.appointmentRepository = appointmentRepository;
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.highThreshold = highThreshold;
        this.mediumThreshold = mediumThreshold;
    }

    @Scheduled(cron = "${kmosf.frontdesk.noshow-scoring.nightly-cron:0 45 2 * * *}")
    public void nightlyRun() {
        tenantRepository.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE
                        && t.getEnabledModules() != null
                        && t.getEnabledModules().contains(FrontDeskAutoConfiguration.MODULE_KEY))
                .flatMap(tenant -> {
                    FrontDeskScoringJob job = FrontDeskScoringJob.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenant.getId())
                            .status(FrontDeskScoringJob.JobStatus.PENDING)
                            .build();
                    return jobRepository.save(job)
                            .flatMap(saved -> runJobForTenant(tenant.getId(), saved))
                            .onErrorResume(err -> {
                                log.warn("Nightly front-desk no-show scoring failed for tenant {}: {}",
                                        tenant.getId(), err.getMessage());
                                return Mono.empty();
                            });
                })
                .subscribe();
    }

    public Mono<FrontDeskScoringJob> triggerRetrain(UUID tenantId) {
        return jobRepository
                .findFirstByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, FrontDeskScoringJob.JobStatus.RUNNING)
                .flatMap(existing -> Mono.<FrontDeskScoringJob>error(
                        new DigiPresBeException("A no-show retrain job is already running for this tenant",
                                4275, 409)))
                .switchIfEmpty(Mono.defer(() -> {
                    FrontDeskScoringJob job = FrontDeskScoringJob.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .status(FrontDeskScoringJob.JobStatus.PENDING)
                            .build();
                    return jobRepository.save(job)
                            .flatMap(saved -> {
                                runJobForTenant(tenantId, saved)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                null,
                                                err -> log.warn("Manual front-desk no-show retrain failed "
                                                        + "for tenant {}: {}", tenantId, err.getMessage()));
                                return Mono.just(saved);
                            });
                }));
    }

    public Mono<Void> runJobForTenant(UUID tenantId, FrontDeskScoringJob job) {
        Mono<FrontDeskScoringJob> markRunning = jobRepository.save(job.toBuilder()
                .status(FrontDeskScoringJob.JobStatus.RUNNING)
                .startedAt(Instant.now())
                .build());

        Mono<List<Appointment>> apptsMono = appointmentRepository.findAllByTenantId(tenantId).collectList();

        return markRunning
                .then(apptsMono)
                .flatMap(appts -> Mono.fromCallable(() -> scoreUpcoming(appts))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(Flux::fromIterable)
                .flatMap(appt -> appointmentRepository.save(appt)
                        .doOnSuccess(saved -> {
                            if (saved.getNoShowRisk() != null) {
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("appointmentId", saved.getId());
                                payload.put("contactId", saved.getContactId());
                                payload.put("providerId", saved.getProviderId());
                                payload.put("riskTier", saved.getNoShowRisk().riskTier());
                                payload.put("riskScore", saved.getNoShowRisk().riskScore());
                                payload.put("source", saved.getNoShowRisk().source());
                                eventPublisher.publish(DomainEvent.of(
                                        DomainEventType.APPOINTMENT_RISK_SCORED,
                                        tenantId, saved.getId(), payload));
                            }
                        }))
                .count()
                .flatMap(count -> jobRepository.save(job.toBuilder()
                        .status(FrontDeskScoringJob.JobStatus.DONE)
                        .appointmentsScored((int) (long) count)
                        .completedAt(Instant.now())
                        .build()))
                .onErrorResume(err -> {
                    log.error("Front-desk no-show scoring job {} failed: {}", job.getId(), err.getMessage(), err);
                    return jobRepository.save(job.toBuilder()
                            .status(FrontDeskScoringJob.JobStatus.FAILED)
                            .errorMessage(err.getMessage())
                            .completedAt(Instant.now())
                            .build());
                })
                .then();
    }

    // ── the model (mirror of the chairfill scorer's scoreUpcoming/features/trainModel/scoreWith*) ──

    /**
     * Trains on terminal appointments (COMPLETED=0, NO_SHOW=1; CANCELLED ignored) and returns the UPCOMING
     * appointments (SCHEDULED|CONFIRMED, scheduledStart in the future) each stamped with a
     * {@link NoShowRisk}. Only upcoming appointments are returned — terminal ones are never re-saved or
     * re-emitted. Runs entirely on {@link Schedulers#boundedElastic()} via {@code Mono.fromCallable} (never
     * the Netty loop): the Smile train/predict and the feature grouping are CPU-bound.
     */
    List<Appointment> scoreUpcoming(List<Appointment> all) {
        Instant now = Instant.now();

        // Terminal appointments (the training universe + the history for the feature priors), grouped by
        // contact and sorted chronologically so priors are computed as-of each appointment.
        List<Appointment> terminal = all.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED
                        || a.getStatus() == AppointmentStatus.NO_SHOW)
                .filter(a -> a.getScheduledStart() != null)
                .sorted(Comparator.comparing(Appointment::getScheduledStart))
                .collect(Collectors.toList());

        Map<UUID, List<Appointment>> terminalByContact = terminal.stream()
                .filter(a -> a.getContactId() != null)
                .collect(Collectors.groupingBy(Appointment::getContactId));

        // Build the training set: features AS OF each terminal appointment's scheduledStart, using only
        // that contact's STRICTLY-EARLIER terminal appointments (no future leakage).
        List<double[]> trainingFeatures = new ArrayList<>();
        List<Integer> trainingLabels = new ArrayList<>();
        for (Appointment a : terminal) {
            List<Appointment> priorHistory = priorHistoryAsOf(terminalByContact, a);
            trainingFeatures.add(features(a, priorHistory));
            trainingLabels.add(a.getStatus() == AppointmentStatus.NO_SHOW ? 1 : 0);
        }

        final LogisticRegression model = (trainingFeatures.size() >= MIN_APPTS_FOR_MODEL
                && hasBothClasses(trainingLabels))
                ? trainModel(trainingFeatures, trainingLabels)
                : null;

        if (model != null) {
            log.info("Front-desk no-show scoring: trained LogisticRegression on {} terminal appointments",
                    trainingFeatures.size());
        } else {
            log.info("Front-desk no-show scoring: {} terminal appointments — using rules fallback (need {})",
                    trainingFeatures.size(), MIN_APPTS_FOR_MODEL);
        }

        // Score every UPCOMING appointment. History for the priors = ALL of that contact's terminal
        // appointments (as of "now"); the appointment being scored is upcoming so it is not in that set.
        return all.stream()
                .filter(a -> (a.getStatus() == AppointmentStatus.SCHEDULED
                        || a.getStatus() == AppointmentStatus.CONFIRMED))
                .filter(a -> a.getScheduledStart() != null && a.getScheduledStart().isAfter(now))
                .map(a -> {
                    List<Appointment> history = a.getContactId() != null
                            ? terminalByContact.getOrDefault(a.getContactId(), List.of())
                            : List.of();
                    double[] f = features(a, history);
                    NoShowRisk risk = (model != null) ? scoreWithModel(model, f, now) : scoreWithRules(f, now);
                    return a.toBuilder().noShowRisk(risk).build();
                })
                .collect(Collectors.toList());
    }

    /** That contact's terminal appointments strictly before {@code target} (chronological history). */
    private List<Appointment> priorHistoryAsOf(Map<UUID, List<Appointment>> terminalByContact,
                                               Appointment target) {
        if (target.getContactId() == null) return List.of();
        List<Appointment> all = terminalByContact.getOrDefault(target.getContactId(), List.of());
        List<Appointment> prior = new ArrayList<>();
        for (Appointment a : all) {
            if (a.getId() != null && a.getId().equals(target.getId())) continue;
            if (a.getScheduledStart() != null
                    && a.getScheduledStart().isBefore(target.getScheduledStart())) {
                prior.add(a);
            }
        }
        return prior;
    }

    /**
     * The PHI-free 9-feature vector (FD-1 §3, fence F1), all derivable from {@link Appointment} logistics
     * metadata + the contact's terminal-appointment history:
     * <pre>
     * [0] priorNoShowRate              prior NO_SHOW / prior terminal, this contact, as-of (no leakage)
     * [1] priorAppointmentCount        count of prior terminal appointments
     * [2] leadTimeHours                max(0, scheduledStart - createdAt) in hours
     * [3] dayOfWeek                    scheduledStart UTC, Mon=1..Sun=7 -> 1..6,0 (CF-1's encoding)
     * [4] hourOfDay                    scheduledStart UTC hour
     * [5] visitTypeBucket              VisitTypeBucket.ordinal() — a closed logistics enum, NEVER a diagnosis
     * [6] insuranceVerificationPending 1.0 if pending else 0.0 (a known no-show correlate; pure logistics)
     * [7] reminderCount                # confirmations/reminders already sent (engagement proxy)
     * [8] daysSinceLastVisit           capped 365; 365.0 sentinel for no prior visit
     * </pre>
     * <strong>There is no diagnosis/procedure/chief-complaint feature, because no such field exists on
     * {@link Appointment}.</strong>
     *
     * <p>Public so the FD-1 PHI-fence IT can assert the vector is logistics-only + fixed-width directly (a
     * drift that added a clinical feature would change the width and fail that test). It is a pure function
     * of {@link Appointment} logistics metadata — exposing it leaks nothing.
     */
    public double[] features(Appointment a, List<Appointment> priorHistory) {
        long priorNoShows = priorHistory.stream()
                .filter(p -> p.getStatus() == AppointmentStatus.NO_SHOW).count();
        long priorTerminal = priorHistory.size();
        double priorNoShowRate = priorTerminal > 0 ? (double) priorNoShows / priorTerminal : 0.0;
        double priorAppointmentCount = (double) priorTerminal;

        long leadTimeHours = 0L;
        if (a.getScheduledStart() != null && a.getCreatedAt() != null) {
            leadTimeHours = Math.max(0L, ChronoUnit.HOURS.between(a.getCreatedAt(), a.getScheduledStart()));
        }

        int dayOfWeek = 0;
        int hourOfDay = 0;
        if (a.getScheduledStart() != null) {
            ZonedDateTime z = a.getScheduledStart().atZone(ZoneOffset.UTC);
            dayOfWeek = z.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7 -> 1..6,0
            hourOfDay = z.getHour();
        }

        double visitTypeBucket = a.getVisitTypeBucket() != null
                ? a.getVisitTypeBucket().ordinal() : 0.0;
        double insuranceVerificationPending = a.isInsuranceVerificationPending() ? 1.0 : 0.0;
        double reminderCount = (double) a.getReminderCount();

        double daysSinceLastVisit = daysSinceLastVisit(a, priorHistory);

        return new double[]{
                priorNoShowRate,
                priorAppointmentCount,
                (double) leadTimeHours,
                (double) dayOfWeek,
                (double) hourOfDay,
                visitTypeBucket,
                insuranceVerificationPending,
                reminderCount,
                daysSinceLastVisit
        };
    }

    /**
     * Days since the contact's most-recent prior visit, capped at 365. Prefers the explicit
     * {@link Appointment#getLastVisitAt()} metadata timestamp (the recall signal) and falls back to the
     * most-recent prior terminal appointment. A large value (and the {@code 365.0} no-history sentinel)
     * marks a cold/lapsed patient, who no-shows more. <em>A metadata timestamp — never a clinical reason
     * for the return visit.</em>
     */
    private double daysSinceLastVisit(Appointment a, List<Appointment> priorHistory) {
        Instant ref = a.getScheduledStart() != null ? a.getScheduledStart() : Instant.now();
        Instant lastVisit = null;

        if (a.getLastVisitAt() != null && a.getLastVisitAt().isBefore(ref)) {
            lastVisit = a.getLastVisitAt();
        }
        for (Appointment p : priorHistory) {
            if (p.getScheduledStart() == null) continue;
            if (p.getScheduledStart().isBefore(ref)
                    && (lastVisit == null || p.getScheduledStart().isAfter(lastVisit))) {
                lastVisit = p.getScheduledStart();
            }
        }
        if (lastVisit == null) return 365.0;
        long days = Math.max(0L, ChronoUnit.DAYS.between(lastVisit, ref));
        return Math.min(365.0, (double) days);
    }

    private boolean hasBothClasses(List<Integer> labels) {
        boolean hasPos = labels.contains(1);
        boolean hasNeg = labels.contains(0);
        return hasPos && hasNeg;
    }

    private LogisticRegression trainModel(List<double[]> featuresList, List<Integer> labels) {
        double[][] x = featuresList.toArray(new double[0][]);
        int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
        // 0 = COMPLETED, 1 = NO_SHOW; posterior[1] is the no-show probability used as the risk score.
        return LogisticRegression.fit(x, y);
    }

    private NoShowRisk scoreWithModel(LogisticRegression model, double[] f, Instant now) {
        double[] posterior = new double[2];
        model.predict(f, posterior);
        double score = posterior[1];
        return new NoShowRisk(score, NoShowRisk.tierFrom(score, highThreshold, mediumThreshold),
                NoShowRisk.SOURCE_MODEL, now);
    }

    /**
     * Deterministic cold-start rules (FD-1 §3) — the night-one path before a model can train, adapted from
     * CF-1's ladder to the PHI-free health vector. <strong>There is no deposit concept</strong> (health
     * doesn't deposit); the long-lead signal is gated on no confirmation/reminder yet
     * ({@code reminderCount == 0}) instead. Feature indices: 0 priorNoShowRate, 1 priorAppointmentCount,
     * 2 leadTimeHours, 7 reminderCount, 8 daysSinceLastVisit ({@code 365.0} = no prior visit sentinel).
     *
     * <p>Evaluated in priority order:
     * <ol>
     *   <li><strong>HIGH</strong> if {@code priorNoShowRate >= 0.34} OR ({@code leadTimeHours > 336}
     *       long-lead AND {@code reminderCount == 0} — a far-out, unconfirmed appointment is the classic
     *       forgotten slot, high-risk even with no history, so checked <em>before</em> the no-history
     *       fallback).</li>
     *   <li><strong>INSUFFICIENT_DATA → LOW</strong> for a true first-timer with no usable evidence (no
     *       prior appointments, no prior visit) — never over-flag a brand-new patient on zero evidence.</li>
     *   <li><strong>MEDIUM</strong> if {@code priorNoShowRate > 0} OR a <em>real</em> lapsed patient
     *       ({@code priorAppointmentCount > 0 && daysSinceLastVisit > 90}); the lapsed branch is gated on
     *       prior history so the {@code 365.0} no-visit sentinel never trips it.</li>
     *   <li><strong>LOW</strong> otherwise.</li>
     * </ol>
     * The rules only set a tier; FD-2 decides the action (a confirmation ask vs a light reminder).
     */
    private NoShowRisk scoreWithRules(double[] f, Instant now) {
        double priorNoShowRate = f[0];
        double priorAppointmentCount = f[1];
        double leadTimeHours = f[2];
        double reminderCount = f[7];
        double daysSinceLastVisit = f[8];

        // HIGH first — a far-out, unconfirmed appointment is high-risk regardless of history.
        if (priorNoShowRate >= 0.34 || (leadTimeHours > 336.0 && reminderCount == 0.0)) {
            return new NoShowRisk(0.8, NoShowRisk.TIER_HIGH, NoShowRisk.SOURCE_RULES_FALLBACK, now);
        }

        // No usable evidence -> INSUFFICIENT_DATA, actioned as LOW (never over-flag a brand-new patient).
        boolean noHistory = priorAppointmentCount == 0.0 && daysSinceLastVisit >= 365.0;
        if (noHistory) {
            return new NoShowRisk(0.2, NoShowRisk.TIER_LOW, NoShowRisk.SOURCE_INSUFFICIENT_DATA, now);
        }

        // Lapsed only counts when there IS prior history (the 365 sentinel must not trip this).
        boolean lapsed = priorAppointmentCount > 0.0 && daysSinceLastVisit > 90.0;
        if (priorNoShowRate > 0.0 || lapsed) {
            return new NoShowRisk(0.5, NoShowRisk.TIER_MEDIUM, NoShowRisk.SOURCE_RULES_FALLBACK, now);
        }
        return new NoShowRisk(0.2, NoShowRisk.TIER_LOW, NoShowRisk.SOURCE_RULES_FALLBACK, now);
    }
}
