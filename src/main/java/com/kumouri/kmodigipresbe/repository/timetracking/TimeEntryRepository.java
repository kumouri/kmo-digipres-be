package com.kumouri.kmodigipresbe.repository.timetracking;

import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit-tenant finders per the Phase-C {@code ProjectRepository} precedent.
 * Auto-scoped base-repo methods ({@code findById}, {@code save}, etc.) apply the
 * {@code tenantId} predicate automatically; derived finders pass {@code tenantId}
 * explicitly for index-friendliness (D-D2).
 *
 * <p>{@link #findFirstByTenantIdAndUserIdAndEndedAtIsNull} is the running-timer
 * lookup — at most one running timer per {@code (tenantId, userId)} is the invariant
 * enforced by the explicit boolean guard in
 * {@link com.kumouri.kmodigipresbe.service.timetracking.TimeEntryService}
 * (not by a unique index — sparse-null-unique pitfall, D-D2 rejected).
 */
public interface TimeEntryRepository extends TenantScopedReactiveMongoRepository<TimeEntry, UUID> {

    Flux<TimeEntry> findAllByTenantIdAndUserIdOrderByStartedAtDesc(UUID tenantId, UUID userId);

    Flux<TimeEntry> findAllByTenantIdAndUserIdAndStartedAtBetweenOrderByStartedAtAsc(
            UUID tenantId, UUID userId, Instant from, Instant to);

    /**
     * The running-timer lookup. Returns at most one row ({@code endedAt == null}
     * means the timer is still running). Used in
     * {@code startTimer} (check before create) and {@code stopTimer} (check before close).
     */
    Mono<TimeEntry> findFirstByTenantIdAndUserIdAndEndedAtIsNull(UUID tenantId, UUID userId);

    Mono<TimeEntry> findByTenantIdAndId(UUID tenantId, UUID id);

    Flux<TimeEntry> findAllByTenantIdAndProjectIdAndBillingStatus(
            UUID tenantId, UUID projectId, BillingStatus billingStatus);

    Flux<TimeEntry> findAllByTenantIdAndTaskIdAndBillingStatus(
            UUID tenantId, UUID taskId, BillingStatus billingStatus);
}
