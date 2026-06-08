package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link Listing} (RE-1).
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the
 * inbound-SMS router runs OUTSIDE a request context (it establishes a synthetic {@code TenantContext}
 * per inbound webhook), so the tenant must be passed explicitly.
 */
public interface ListingRepository extends TenantScopedReactiveMongoRepository<Listing, UUID> {

    /** Resolve a listing by its tracked Twilio number — the primary inbound-SMS correlation key. */
    Mono<Listing> findByTenantIdAndTrackedPhone(UUID tenantId, String trackedPhone);

    /** All listings for a tenant, newest first — the agent console list. */
    Flux<Listing> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Mono<Listing> findByIdAndTenantId(UUID id, UUID tenantId);
}
