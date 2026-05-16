package com.kumouri.kmodigipresbe.repository.timetracking;

import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.Expense.ApprovalStatus;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Explicit-tenant finders per the Phase-C {@code ProjectRepository} precedent (D-D2).
 */
public interface ExpenseRepository extends TenantScopedReactiveMongoRepository<Expense, UUID> {

    Flux<Expense> findAllByTenantIdAndUserIdOrderByIncurredOnDesc(UUID tenantId, UUID userId);

    Mono<Expense> findByTenantIdAndId(UUID tenantId, UUID id);

    Flux<Expense> findAllByTenantIdAndApprovalStatus(UUID tenantId, ApprovalStatus approvalStatus);

    Flux<Expense> findAllByTenantIdAndProjectIdAndApprovalStatusAndBillingStatus(
            UUID tenantId, UUID projectId, ApprovalStatus approvalStatus, BillingStatus billingStatus);
}
