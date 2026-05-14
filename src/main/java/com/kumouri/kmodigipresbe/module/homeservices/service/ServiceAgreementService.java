package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RequiredArgsConstructor
public class ServiceAgreementService {

    private final ServiceAgreementRepository agreements;
    private final ServiceAgreementSchedulerService scheduler;

    public Flux<ServiceAgreement> findAll() {
        return agreements.findAll();
    }

    public Mono<ServiceAgreement> findById(UUID id) {
        return agreements.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "ServiceAgreement not found", 2710, 404)));
    }

    public Mono<ServiceAgreement> create(ServiceAgreement toCreate) {
        toCreate.setId(null);
        if (toCreate.getStatus() == null) toCreate.setStatus(ServiceAgreementStatus.DRAFT);
        return agreements.save(toCreate);
    }

    public Mono<ServiceAgreement> update(UUID id, ServiceAgreement patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getContactId() != null) existing.setContactId(patch.getContactId());
            if (patch.getJobSiteId() != null) existing.setJobSiteId(patch.getJobSiteId());
            if (patch.getAgreementType() != null) existing.setAgreementType(patch.getAgreementType());
            if (patch.getStartDate() != null) existing.setStartDate(patch.getStartDate());
            if (patch.getEndDate() != null) existing.setEndDate(patch.getEndDate());
            if (patch.getRecurrenceRule() != null) existing.setRecurrenceRule(patch.getRecurrenceRule());
            if (patch.getIncludedServices() != null) existing.setIncludedServices(patch.getIncludedServices());
            if (patch.getPriceListId() != null) existing.setPriceListId(patch.getPriceListId());
            if (patch.getBillingCadence() != null) existing.setBillingCadence(patch.getBillingCadence());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return agreements.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return agreements.deleteById(id);
    }

    /**
     * Activate an agreement and materialize the first 90-day batch of
     * {@link com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit}
     * rows synchronously, so the API caller sees scheduled visits immediately
     * (the tick would otherwise lag by up to {@code kmosf.home-services.scheduler.tick-ms}).
     */
    public Mono<ServiceAgreement> activate(UUID id) {
        return findById(id).flatMap(agreement -> {
            if (agreement.getStatus() == ServiceAgreementStatus.ACTIVE) {
                return Mono.just(agreement);
            }
            if (agreement.getStatus() == ServiceAgreementStatus.CANCELLED) {
                return Mono.error(new DigiPresBeException(
                        "Cancelled agreement cannot be reactivated", 2711, 409));
            }
            agreement.setStatus(ServiceAgreementStatus.ACTIVE);
            return agreements.save(agreement)
                    .flatMap(saved -> scheduler.materializeFor(saved).thenReturn(saved));
        });
    }

    public Mono<ServiceAgreement> pause(UUID id) {
        return findById(id).flatMap(agreement -> {
            if (agreement.getStatus() == ServiceAgreementStatus.CANCELLED) {
                return Mono.error(new DigiPresBeException(
                        "Cancelled agreement cannot be paused", 2712, 409));
            }
            agreement.setStatus(ServiceAgreementStatus.PAUSED);
            return agreements.save(agreement);
        });
    }

    /**
     * Re-run the 90-day materialization for one agreement. Idempotent — the
     * unique compound index on {@code maintenance_visits} prevents duplicates.
     */
    public Mono<ServiceAgreement> regenerateVisits(UUID id) {
        return findById(id).flatMap(agreement ->
                scheduler.materializeFor(agreement).thenReturn(agreement));
    }
}
