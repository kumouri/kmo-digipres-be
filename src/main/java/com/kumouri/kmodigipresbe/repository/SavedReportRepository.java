package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.report.SavedReport;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SavedReportRepository
        extends TenantScopedReactiveMongoRepository<SavedReport, UUID> {

    Flux<SavedReport> findAllByTenantIdAndEntityType(UUID tenantId, String entityType);

    /** Scheduler scans this across tenants — no tenant filter at the query level. */
    @org.springframework.data.mongodb.repository.Query(
            "{ 'scheduleCron': { $ne: null } }")
    Flux<SavedReport> findAllScheduled();
}
