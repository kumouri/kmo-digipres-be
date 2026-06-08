package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SequenceRepository extends TenantScopedReactiveMongoRepository<Sequence, UUID> {

    /**
     * All sequences with the given {@code status} for a tenant. Carries an explicit {@code tenantId}
     * predicate because the {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived
     * finders — used by the frontdesk {@code RecallDetectorJob}, which runs OUTSIDE a request
     * {@code TenantContext} (it establishes a synthetic one per tenant), to find a tenant's ACTIVE recall
     * Sequence by name.
     */
    Flux<Sequence> findAllByTenantIdAndStatus(UUID tenantId, Sequence.Status status);
}
