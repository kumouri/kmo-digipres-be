package com.kumouri.kmodigipresbe.module.homeservices.repository;

import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface MaintenanceVisitRepository
        extends TenantScopedReactiveMongoRepository<MaintenanceVisit, UUID> {

    Flux<MaintenanceVisit> findAllByTenantIdAndServiceAgreementId(
            UUID tenantId, UUID serviceAgreementId);

    Flux<MaintenanceVisit> findAllByTenantIdAndStatus(
            UUID tenantId, MaintenanceVisitStatus status);

    Flux<MaintenanceVisit> findAllByTenantIdAndScheduledStartBetween(
            UUID tenantId, Instant from, Instant to);
}
