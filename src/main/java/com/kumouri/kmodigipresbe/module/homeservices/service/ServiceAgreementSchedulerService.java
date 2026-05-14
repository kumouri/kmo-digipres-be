package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
import com.kumouri.kmodigipresbe.service.scheduling.RecurringSchedule;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Materializes {@link MaintenanceVisit} rows from each {@link ServiceAgreement}'s
 * {@code recurrenceRule}, 90 days into the future. Runs on a fixed-rate tick
 * (default 1 hour). Per-agreement processing establishes a synthetic
 * {@link TenantContext} so {@code TenantStampingCallback} stamps the saved
 * visits correctly; the (tenantId, serviceAgreementId, scheduledStart) unique
 * compound index makes re-runs idempotent — duplicate-key errors are swallowed.
 *
 * <p>Mirrors the {@code ReportScheduler} tick pattern from Phase 9e.
 * Bad-RRULE agreements are logged and skipped, not blocking the rest of the
 * tick.
 */
@Slf4j
@RequiredArgsConstructor
public class ServiceAgreementSchedulerService {

    public static final String SYSTEM_ROLE = "AGREEMENT_SCHEDULER";
    private static final Duration MATERIALIZE_WINDOW = Duration.ofDays(90);

    private final ServiceAgreementRepository agreements;
    private final MaintenanceVisitRepository visits;
    private final RecurringSchedule recurringSchedule;
    private final Clock clock;

    @Scheduled(
            fixedRateString = "${kmosf.home-services.scheduler.tick-ms:3600000}",
            initialDelayString = "${kmosf.home-services.scheduler.initial-delay-ms:60000}")
    public void tick() {
        runDueOnce()
                .onErrorContinue((err, evt) ->
                        log.warn("ServiceAgreementSchedulerService tick dropped {}: {}",
                                evt, err.toString()))
                .subscribe();
    }

    /** Visible for tests — runs one materialization pass across all active tenants. */
    public Mono<Void> runDueOnce() {
        return agreements.findAllActiveAcrossTenants()
                .flatMap(this::materializeForSafe)
                .then();
    }

    /**
     * Materialize occurrences for one agreement, swallowing per-agreement errors
     * so a single bad row can't poison the tick.
     */
    private Mono<Void> materializeForSafe(ServiceAgreement agreement) {
        return materializeFor(agreement)
                .onErrorResume(ex -> {
                    log.warn("ServiceAgreementSchedulerService skipped agreement {}: {}",
                            agreement.getId(), ex.toString());
                    return Mono.empty();
                });
    }

    /**
     * Materialize occurrences for one agreement. Public so {@code ServiceAgreementService.activate}
     * can call it synchronously and surface bad-RRULE failures back through the
     * normal {@link DigiPresBeException} translation path.
     *
     * <p>{@code Rfc5545RecurringSchedule.expand} throws synchronously on a bad RRULE
     * (mapped to {@code DigiPresBeException(1300, 400)}); wrap the call in
     * {@code Mono.fromCallable} so the exception is delivered through the Mono
     * pipeline and {@link #materializeForSafe} can swallow it.
     */
    public Mono<Void> materializeFor(ServiceAgreement agreement) {
        if (agreement.getRecurrenceRule() == null || agreement.getRecurrenceRule().isBlank()) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(
                agreement.getTenantId(), null, Set.of(SYSTEM_ROLE));
        Instant now = clock.instant();
        Instant to = now.plus(MATERIALIZE_WINDOW);
        Instant seed = agreement.getStartDate().atStartOfDay(ZoneOffset.UTC).toInstant();

        return Mono.fromCallable(() -> recurringSchedule.expand(
                        agreement.getRecurrenceRule(), seed, now, to))
                .flatMapMany(Flux::fromIterable)
                .flatMap(scheduledStart -> {
                    MaintenanceVisit visit = MaintenanceVisit.builder()
                            .serviceAgreementId(agreement.getId())
                            .jobSiteId(agreement.getJobSiteId())
                            .scheduledStart(scheduledStart)
                            .status(MaintenanceVisitStatus.SCHEDULED)
                            .build();
                    return visits.save(visit)
                            .onErrorResume(DuplicateKeyException.class, e -> Mono.empty());
                })
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }
}
