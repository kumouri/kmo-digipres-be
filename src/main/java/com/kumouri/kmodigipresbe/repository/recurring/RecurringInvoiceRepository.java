package com.kumouri.kmodigipresbe.repository.recurring;

import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice.Status;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit-tenant finders per the Phase-C/D precedent. Auto-scoped base-repo
 * methods ({@code findById}, {@code save}, ...) apply the {@code tenantId}
 * predicate automatically; derived finders pass {@code tenantId} explicitly for
 * index-friendliness (E-D2).
 *
 * <p>{@link #findAllDueAcrossTenants} is the scheduler's cross-tenant due-scan: the
 * {@code @Query} bypasses the tenant-filter wired into
 * {@code TenantScopedSimpleReactiveMongoRepository} so the spawn tick can run
 * without a per-tenant outer context (the synthetic context is established
 * per-row inside {@code RecurringInvoiceSpawnService}). This is exactly the
 * {@code ServiceAgreementRepository.findAllActiveAcrossTenants} precedent, here
 * additionally filtered to {@code nextRunAt <= now} so only due rows are scanned.
 */
public interface RecurringInvoiceRepository
        extends TenantScopedReactiveMongoRepository<RecurringInvoice, UUID> {

    Mono<RecurringInvoice> findByTenantIdAndId(UUID tenantId, UUID id);

    Flux<RecurringInvoice> findAllByTenantIdAndStatus(UUID tenantId, Status status);

    Flux<RecurringInvoice> findAllByTenantId(UUID tenantId);

    /**
     * Cross-tenant due-scan for the spawn tick: ACTIVE recurring invoices whose
     * {@code nextRunAt} is at or before {@code now}. The {@code @Query} deliberately
     * carries no {@code tenantId} predicate — the scheduler has no outer tenant
     * context; a synthetic per-tenant context is established inside the service for
     * each row before any save (the {@code ServiceAgreementSchedulerService} model).
     */
    @Query("{ 'status': 'ACTIVE', 'nextRunAt': { '$ne': null, '$lte': ?0 } }")
    Flux<RecurringInvoice> findAllDueAcrossTenants(Instant now);
}
