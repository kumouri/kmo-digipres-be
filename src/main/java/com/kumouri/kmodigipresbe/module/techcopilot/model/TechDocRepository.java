package com.kumouri.kmodigipresbe.module.techcopilot.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link TechDoc} (T13).
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does not auto-scope derived finders).
 */
public interface TechDocRepository extends TenantScopedReactiveMongoRepository<TechDoc, UUID> {

    /** All docs for a tenant, newest first — the corpus console list. */
    Flux<TechDoc> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Mono<TechDoc> findByIdAndTenantId(UUID id, UUID tenantId);
}
