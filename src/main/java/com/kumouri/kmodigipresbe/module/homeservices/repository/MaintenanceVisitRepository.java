package com.kumouri.kmodigipresbe.module.homeservices.repository;

import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface MaintenanceVisitRepository
        extends TenantScopedReactiveMongoRepository<MaintenanceVisit, UUID> {
}
