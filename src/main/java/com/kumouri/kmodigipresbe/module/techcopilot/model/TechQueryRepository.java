package com.kumouri.kmodigipresbe.module.techcopilot.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link TechQuery} (T13).
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does not auto-scope derived finders).
 */
public interface TechQueryRepository extends TenantScopedReactiveMongoRepository<TechQuery, UUID> {

    /** Recent Q&amp;A for a tenant, newest first — the dashboard read. */
    Flux<TechQuery> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Mono<TechQuery> findByIdAndTenantId(UUID id, UUID tenantId);
}
