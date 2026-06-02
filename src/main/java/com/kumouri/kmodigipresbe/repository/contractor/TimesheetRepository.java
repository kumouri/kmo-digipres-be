package com.kumouri.kmodigipresbe.repository.contractor;

import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet.Status;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Explicit-tenant finders per the Phase-C {@code ProjectRepository} precedent.
 */
public interface TimesheetRepository
        extends TenantScopedReactiveMongoRepository<Timesheet, UUID> {

    Mono<Timesheet> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Find-or-create-open-period lookup (at most one — unique {@code tenant_user_period_idx}). */
    Mono<Timesheet> findFirstByTenantIdAndUserIdAndPeriodStart(
            UUID tenantId, UUID userId, LocalDate periodStart);

    Flux<Timesheet> findAllByTenantIdAndUserIdOrderByPeriodStartDesc(UUID tenantId, UUID userId);

    /** Admin review queue (Phase J — timesheet approval). */
    Flux<Timesheet> findAllByTenantIdAndStatus(UUID tenantId, Status status);
}
