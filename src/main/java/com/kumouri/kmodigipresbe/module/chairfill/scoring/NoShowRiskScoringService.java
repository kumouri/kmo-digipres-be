package com.kumouri.kmodigipresbe.module.chairfill.scoring;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowScoringJob;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.repository.NoShowScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import smile.classification.LogisticRegression;

import java.math.BigDecimal;
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
 * ChairFill CF-1 — nightly, per-tenant, ML no-show-risk scoring for salon bookings.
 *
 * <p><strong>A parallel fork of {@link com.kumouri.kmodigipresbe.service.ai.scoring.LeadScoringV2Service},
 * NOT a generalization of it (CF-1 D1).</strong> Same machine — nightly {@link Scheduled} per-tenant,
 * a {@code >= MIN_BOOKINGS_FOR_MODEL}-sample model-vs-rules gate, a Smile {@link LogisticRegression},
 * a job ledger, {@link Schedulers#boundedElastic()} for the blocking ML work, and a domain-event emit —
 * but pointed at salon {@link Booking}s instead of leads. This keeps the shipped, NMM-relied-upon
 * lead-scorer regression-proof.
 *
 * <p><strong>Label polarity is the mirror-image of the lead-scorer's WON=1:</strong> here the training
 * label per <em>terminal</em> booking is {@code NO_SHOW = 1} / {@code COMPLETED = 0} (CANCELLED is
 * excluded — a cancel is not a no-show), so the predicted score is P(no-show).
 *
 * <p>Wired as a {@code @Bean} in {@link ChairFillAutoConfiguration} (the salon-spa hand-construction
 * pattern), so it exists only when {@code kmosf.modules.chairfill.enabled=true}. The nightly run
 * additionally filters to tenants whose {@code enabledModules} contains {@code "chairfill"}, so a
 * salon tenant without ChairFill — and every non-salon tenant — is skipped: blast radius zero.
 */
@Slf4j
public class NoShowRiskScoringService {

    /**
     * Minimum terminal (COMPLETED/NO_SHOW) bookings before a model is trained. Slightly below
     * the lead-scorer's 50 because salons accumulate terminal bookings faster than closed deals,
     * so we want a model sooner (CF-1 D1).
     */
    static final int MIN_BOOKINGS_FOR_MODEL = 40;

    private final TenantRepository tenantRepository;
    private final BookingRepository bookingRepository;
    private final ServiceMenuRepository serviceMenuRepository;
    private final NoShowScoringJobRepository jobRepository;
    private final DomainEventPublisher eventPublisher;

    private final double highThreshold;
    private final double mediumThreshold;

    public NoShowRiskScoringService(TenantRepository tenantRepository,
                                    BookingRepository bookingRepository,
                                    ServiceMenuRepository serviceMenuRepository,
                                    NoShowScoringJobRepository jobRepository,
                                    DomainEventPublisher eventPublisher,
                                    double highThreshold,
                                    double mediumThreshold) {
        this.tenantRepository = tenantRepository;
        this.bookingRepository = bookingRepository;
        this.serviceMenuRepository = serviceMenuRepository;
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
        this.highThreshold = highThreshold;
        this.mediumThreshold = mediumThreshold;
    }

    @Scheduled(cron = "${kmosf.chairfill.noshow-scoring.nightly-cron:0 30 2 * * *}")
    public void nightlyRun() {
        tenantRepository.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE
                        && t.getEnabledModules() != null
                        && t.getEnabledModules().contains(ChairFillAutoConfiguration.MODULE_KEY))
                .flatMap(tenant -> {
                    NoShowScoringJob job = NoShowScoringJob.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenant.getId())
                            .status(NoShowScoringJob.JobStatus.PENDING)
                            .build();
                    return jobRepository.save(job)
                            .flatMap(saved -> runJobForTenant(tenant.getId(), saved))
                            .onErrorResume(err -> {
                                log.warn("Nightly no-show scoring failed for tenant {}: {}",
                                        tenant.getId(), err.getMessage());
                                return Mono.empty();
                            });
                })
                .subscribe();
    }

    public Mono<NoShowScoringJob> triggerRetrain(UUID tenantId) {
        return jobRepository
                .findFirstByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, NoShowScoringJob.JobStatus.RUNNING)
                .flatMap(existing -> Mono.<NoShowScoringJob>error(
                        new DigiPresBeException("A no-show retrain job is already running for this tenant",
                                4221, 409)))
                .switchIfEmpty(Mono.defer(() -> {
                    NoShowScoringJob job = NoShowScoringJob.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .status(NoShowScoringJob.JobStatus.PENDING)
                            .build();
                    return jobRepository.save(job)
                            .flatMap(saved -> {
                                runJobForTenant(tenantId, saved)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                null,
                                                err -> log.warn("Manual no-show retrain failed for tenant {}: {}",
                                                        tenantId, err.getMessage()));
                                return Mono.just(saved);
                            });
                }));
    }

    public Mono<Void> runJobForTenant(UUID tenantId, NoShowScoringJob job) {
        Mono<NoShowScoringJob> markRunning = jobRepository.save(job.toBuilder()
                .status(NoShowScoringJob.JobStatus.RUNNING)
                .startedAt(Instant.now())
                .build());

        Mono<List<Booking>> bookingsMono = bookingRepository.findAllByTenantId(tenantId).collectList();
        Mono<List<ServiceMenu>> menusMono = serviceMenuRepository.findAllByTenantId(tenantId).collectList();

        return markRunning
                .then(Mono.zip(bookingsMono, menusMono))
                .flatMap(t -> Mono.fromCallable(() -> scoreUpcoming(t.getT1(), t.getT2()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(Flux::fromIterable)
                .flatMap(booking -> bookingRepository.save(booking)
                        .doOnSuccess(saved -> {
                            if (saved.getNoShowRisk() != null) {
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("bookingId", saved.getId());
                                payload.put("contactId", saved.getContactId());
                                payload.put("staffMemberId", saved.getStaffMemberId());
                                payload.put("riskTier", saved.getNoShowRisk().riskTier());
                                payload.put("riskScore", saved.getNoShowRisk().riskScore());
                                payload.put("source", saved.getNoShowRisk().source());
                                eventPublisher.publish(DomainEvent.of(
                                        DomainEventType.BOOKING_RISK_SCORED,
                                        tenantId, saved.getId(), payload));
                            }
                        }))
                .count()
                .flatMap(count -> jobRepository.save(job.toBuilder()
                        .status(NoShowScoringJob.JobStatus.DONE)
                        .bookingsScored((int) (long) count)
                        .completedAt(Instant.now())
                        .build()))
                .onErrorResume(err -> {
                    log.error("No-show scoring job {} failed: {}", job.getId(), err.getMessage(), err);
                    return jobRepository.save(job.toBuilder()
                            .status(NoShowScoringJob.JobStatus.FAILED)
                            .errorMessage(err.getMessage())
                            .completedAt(Instant.now())
                            .build());
                })
                .then();
    }

    // ── the model (mirror of LeadScoringV2Service.scoreAll/features/trainModel/scoreWith*) ──

    /**
     * Trains on terminal bookings (COMPLETED=0, NO_SHOW=1; CANCELLED ignored) and returns the
     * UPCOMING bookings (CONFIRMED|PENDING_DEPOSIT, scheduledStart in the future) each stamped
     * with a {@link NoShowRisk}. Only upcoming bookings are returned — terminal bookings are never
     * re-saved or re-emitted. Runs entirely on {@link Schedulers#boundedElastic()} via
     * {@code Mono.fromCallable} (never the Netty loop): the Smile train/predict and the feature
     * grouping are CPU-bound.
     */
    private List<Booking> scoreUpcoming(List<Booking> all, List<ServiceMenu> menus) {
        Instant now = Instant.now();

        Map<String, ServiceMenuItem> itemsById = new HashMap<>();
        for (ServiceMenu menu : menus) {
            if (menu.getServices() == null) continue;
            for (ServiceMenuItem item : menu.getServices()) {
                if (item.getId() != null) {
                    itemsById.putIfAbsent(item.getId(), item);
                }
            }
        }

        // Terminal bookings (the training universe + the history for the feature priors),
        // grouped by contact and sorted chronologically so priors are computed as-of each booking.
        List<Booking> terminal = all.stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED
                        || b.getStatus() == BookingStatus.NO_SHOW)
                .filter(b -> b.getScheduledStart() != null)
                .sorted(Comparator.comparing(Booking::getScheduledStart))
                .collect(Collectors.toList());

        Map<UUID, List<Booking>> terminalByContact = terminal.stream()
                .filter(b -> b.getContactId() != null)
                .collect(Collectors.groupingBy(Booking::getContactId));

        // Build the training set: features AS OF each terminal booking's scheduledStart, using
        // only that contact's STRICTLY-EARLIER terminal bookings (no future leakage).
        List<double[]> trainingFeatures = new ArrayList<>();
        List<Integer> trainingLabels = new ArrayList<>();
        for (Booking b : terminal) {
            List<Booking> priorHistory = priorHistoryAsOf(terminalByContact, b);
            trainingFeatures.add(features(b, priorHistory, itemsById));
            trainingLabels.add(b.getStatus() == BookingStatus.NO_SHOW ? 1 : 0);
        }

        final LogisticRegression model = (trainingFeatures.size() >= MIN_BOOKINGS_FOR_MODEL
                && hasBothClasses(trainingLabels))
                ? trainModel(trainingFeatures, trainingLabels)
                : null;

        if (model != null) {
            log.info("No-show scoring: trained LogisticRegression on {} terminal bookings", trainingFeatures.size());
        } else {
            log.info("No-show scoring: {} terminal bookings — using rules fallback (need {})",
                    trainingFeatures.size(), MIN_BOOKINGS_FOR_MODEL);
        }

        // Score every UPCOMING booking. History for the priors = ALL of that contact's terminal
        // bookings (as of "now"); the booking being scored is upcoming so it is not in that set.
        return all.stream()
                .filter(b -> (b.getStatus() == BookingStatus.CONFIRMED
                        || b.getStatus() == BookingStatus.PENDING_DEPOSIT))
                .filter(b -> b.getScheduledStart() != null && b.getScheduledStart().isAfter(now))
                .map(b -> {
                    List<Booking> history = b.getContactId() != null
                            ? terminalByContact.getOrDefault(b.getContactId(), List.of())
                            : List.of();
                    double[] f = features(b, history, itemsById);
                    NoShowRisk risk = (model != null) ? scoreWithModel(model, f, now) : scoreWithRules(f, now);
                    return b.toBuilder().noShowRisk(risk).build();
                })
                .collect(Collectors.toList());
    }

    /** That contact's terminal bookings strictly before {@code target} (chronological history). */
    private List<Booking> priorHistoryAsOf(Map<UUID, List<Booking>> terminalByContact, Booking target) {
        if (target.getContactId() == null) return List.of();
        List<Booking> all = terminalByContact.getOrDefault(target.getContactId(), List.of());
        List<Booking> prior = new ArrayList<>();
        for (Booking b : all) {
            if (b.getId() != null && b.getId().equals(target.getId())) continue;
            if (b.getScheduledStart() != null && b.getScheduledStart().isBefore(target.getScheduledStart())) {
                prior.add(b);
            }
        }
        return prior;
    }

    /**
     * The 8-feature vector (CF-1 D1), all derivable from {@link Booking} + {@link ServiceMenuItem}
     * + the contact's terminal-booking history:
     * {@code [ priorNoShowRate, priorBookingCount, leadTimeHours, dayOfWeek, hourOfDay,
     * priceBand, depositOnFile, daysSinceLastVisit ]}.
     */
    private double[] features(Booking b, List<Booking> priorHistory, Map<String, ServiceMenuItem> itemsById) {
        long priorNoShows = priorHistory.stream().filter(p -> p.getStatus() == BookingStatus.NO_SHOW).count();
        long priorTerminal = priorHistory.size();
        double priorNoShowRate = priorTerminal > 0 ? (double) priorNoShows / priorTerminal : 0.0;
        double priorBookingCount = (double) priorTerminal;

        long leadTimeHours = 0L;
        if (b.getScheduledStart() != null && b.getCreatedAt() != null) {
            leadTimeHours = Math.max(0L, ChronoUnit.HOURS.between(b.getCreatedAt(), b.getScheduledStart()));
        }

        int dayOfWeek = 0;
        int hourOfDay = 0;
        if (b.getScheduledStart() != null) {
            ZonedDateTime z = b.getScheduledStart().atZone(ZoneOffset.UTC);
            dayOfWeek = z.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7 -> 1..6,0
            hourOfDay = z.getHour();
        }

        double priceBand = priceBand(b, itemsById);
        double depositOnFile = (b.isDepositRequired() && b.isDepositPaid()) ? 1.0 : 0.0;

        double daysSinceLastVisit = daysSinceLastVisit(b, priorHistory);

        return new double[]{
                priorNoShowRate,
                priorBookingCount,
                (double) leadTimeHours,
                (double) dayOfWeek,
                (double) hourOfDay,
                priceBand,
                depositOnFile,
                daysSinceLastVisit
        };
    }

    /** Bucketed price: 0 (&lt;$30), 1 (&lt;$75), 2 (&lt;$150), 3 (&ge;$150). 0 when unknown. */
    private double priceBand(Booking b, Map<String, ServiceMenuItem> itemsById) {
        ServiceMenuItem item = b.getServiceMenuItemId() != null ? itemsById.get(b.getServiceMenuItemId()) : null;
        if (item == null || item.getPrice() == null) return 0.0;
        BigDecimal p = item.getPrice();
        if (p.compareTo(BigDecimal.valueOf(30)) < 0) return 0.0;
        if (p.compareTo(BigDecimal.valueOf(75)) < 0) return 1.0;
        if (p.compareTo(BigDecimal.valueOf(150)) < 0) return 2.0;
        return 3.0;
    }

    /**
     * Days since the contact's most-recent prior terminal visit, capped at 365. A large value
     * (and the {@code 365.0} no-history sentinel) marks a cold/lapsed client, who no-shows more.
     */
    private double daysSinceLastVisit(Booking b, List<Booking> priorHistory) {
        Instant ref = b.getScheduledStart() != null ? b.getScheduledStart() : Instant.now();
        Instant lastVisit = null;
        for (Booking p : priorHistory) {
            if (p.getScheduledStart() == null) continue;
            if (p.getScheduledStart().isBefore(ref) && (lastVisit == null || p.getScheduledStart().isAfter(lastVisit))) {
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
     * Deterministic cold-start rules (CF-1 D1) — the night-one path before a model can train.
     * Feature indices: 0 priorNoShowRate, 1 priorBookingCount, 2 leadTimeHours, 6 depositOnFile,
     * 7 daysSinceLastVisit ({@code 365.0} is the "no prior visit" sentinel).
     *
     * <p>Evaluated in priority order:
     * <ol>
     *   <li><strong>HIGH</strong> if {@code priorNoShowRate >= 0.34} OR ({@code leadTimeHours > 336}
     *       AND no deposit). The long-lead-no-deposit signal is high-risk even with no history, so
     *       it is checked <em>before</em> the no-history fallback (a far-out, un-deposited booking
     *       is the classic forgotten/flaky slot).</li>
     *   <li><strong>INSUFFICIENT_DATA → LOW</strong> for a first-timer with no usable evidence:
     *       no prior bookings, no prior visit, and no deposit. Never punish a brand-new client with
     *       a deposit demand on zero evidence (CF-1 D1 / §4 cold-start mitigation).</li>
     *   <li><strong>MEDIUM</strong> if {@code priorNoShowRate > 0} OR a <em>real</em> lapsed client
     *       (has prior bookings AND {@code daysSinceLastVisit > 90}). The lapsed branch is gated on
     *       {@code priorBookingCount > 0} so the {@code 365.0} no-visit sentinel never trips it.</li>
     *   <li><strong>LOW</strong> otherwise (strong recent history, or new-with-deposit).</li>
     * </ol>
     */
    private NoShowRisk scoreWithRules(double[] f, Instant now) {
        double priorNoShowRate = f[0];
        double priorBookingCount = f[1];
        double leadTimeHours = f[2];
        double depositOnFile = f[6];
        double daysSinceLastVisit = f[7];

        // HIGH first — a far-out, un-deposited booking is high-risk regardless of history.
        if (priorNoShowRate >= 0.34 || (leadTimeHours > 336.0 && depositOnFile == 0.0)) {
            return new NoShowRisk(0.8, NoShowRisk.TIER_HIGH, NoShowRisk.SOURCE_RULES_FALLBACK, now);
        }

        // No usable evidence -> INSUFFICIENT_DATA, actioned as LOW (never a deposit demand on zero evidence).
        boolean noHistory = priorBookingCount == 0.0 && daysSinceLastVisit >= 365.0 && depositOnFile == 0.0;
        if (noHistory) {
            return new NoShowRisk(0.2, NoShowRisk.TIER_LOW, NoShowRisk.SOURCE_INSUFFICIENT_DATA, now);
        }

        // Lapsed only counts when there IS prior history (the 365 sentinel must not trip this).
        boolean lapsed = priorBookingCount > 0.0 && daysSinceLastVisit > 90.0;
        if (priorNoShowRate > 0.0 || lapsed) {
            return new NoShowRisk(0.5, NoShowRisk.TIER_MEDIUM, NoShowRisk.SOURCE_RULES_FALLBACK, now);
        }
        return new NoShowRisk(0.2, NoShowRisk.TIER_LOW, NoShowRisk.SOURCE_RULES_FALLBACK, now);
    }
}
