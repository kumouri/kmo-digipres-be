package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link ListingDisclosure} (RE-1).
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate (the {@link TenantScopedReactiveMongoRepository}
 * marker does not auto-scope derived finders).
 */
public interface ListingDisclosureRepository
        extends TenantScopedReactiveMongoRepository<ListingDisclosure, UUID> {

    /** All disclosures for a listing (agent console / re-index sweep). */
    Flux<ListingDisclosure> findByTenantIdAndListingId(UUID tenantId, UUID listingId);

    Mono<ListingDisclosure> findByIdAndTenantId(UUID id, UUID tenantId);
}
