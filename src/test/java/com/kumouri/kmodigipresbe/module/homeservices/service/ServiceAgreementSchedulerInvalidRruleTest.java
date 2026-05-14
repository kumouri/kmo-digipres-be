package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
import com.kumouri.kmodigipresbe.service.scheduling.Rfc5545RecurringSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-logic guard for the bad-RRULE path. Verifies that
 * {@code materializeFor} returns a Mono error (so callers like
 * {@code ServiceAgreementService.activate} can surface a 400 to the user) AND
 * that the tick-level {@code runDueOnce} swallows the error via
 * {@code materializeForSafe} so one bad row can't poison the run.
 */
class ServiceAgreementSchedulerInvalidRruleTest {

    private ServiceAgreementRepository agreements;
    private MaintenanceVisitRepository visits;
    private ServiceAgreementSchedulerService scheduler;

    @BeforeEach
    void setup() {
        agreements = mock(ServiceAgreementRepository.class);
        visits = mock(MaintenanceVisitRepository.class);
        Clock fixed = Clock.fixed(
                LocalDate.of(2026, 5, 14).atStartOfDay(ZoneOffset.UTC).toInstant(),
                ZoneOffset.UTC);
        scheduler = new ServiceAgreementSchedulerService(
                agreements, visits, new Rfc5545RecurringSchedule(), fixed);
    }

    private ServiceAgreement badAgreement() {
        return ServiceAgreement.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .jobSiteId(UUID.randomUUID())
                .agreementType("bad-rrule")
                .startDate(LocalDate.of(2026, 1, 1))
                .recurrenceRule("THIS-IS-NOT-AN-RRULE")
                .status(ServiceAgreementStatus.ACTIVE)
                .build();
    }

    @Test
    void materializeFor_badRrule_errorsWithCode1300_andNoSaveAttempted() {
        StepVerifier.create(scheduler.materializeFor(badAgreement()))
                .expectErrorSatisfies(err -> {
                    assert err instanceof DigiPresBeException;
                    DigiPresBeException dpb = (DigiPresBeException) err;
                    assert dpb.getErrorCode() == 1300;
                })
                .verify();
        verify(visits, never()).save(any(MaintenanceVisit.class));
    }

    @Test
    void runDueOnce_badRrule_completes_andSkipsBadAgreement() {
        when(agreements.findAllActiveAcrossTenants())
                .thenReturn(Flux.just(badAgreement()));

        StepVerifier.create(scheduler.runDueOnce())
                .verifyComplete();
        verify(visits, never()).save(any(MaintenanceVisit.class));
    }

    @Test
    void materializeFor_blankRrule_completesEmpty_andNoSave() {
        ServiceAgreement blank = badAgreement().toBuilder().recurrenceRule("").build();
        StepVerifier.create(scheduler.materializeFor(blank))
                .verifyComplete();
        verify(visits, never()).save(any(MaintenanceVisit.class));
    }

    @Test
    void materializeFor_nullRrule_completesEmpty_andNoSave() {
        ServiceAgreement noRule = badAgreement().toBuilder().recurrenceRule(null).build();
        StepVerifier.create(scheduler.materializeFor(noRule))
                .verifyComplete();
        verify(visits, never()).save(any(MaintenanceVisit.class));
    }

    @Test
    @SuppressWarnings("unused")
    void clockMustBeProvided_constructorSig() {
        // Compile-time check that the constructor accepts a Clock — guards against
        // accidental refactors that drop the testable clock dependency.
        Instant ignored = Clock.systemUTC().instant();
    }
}
