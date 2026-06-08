package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Repository for {@link ListingPhoto} (RE-4 — Marketing Studio).
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate (the {@link TenantScopedReactiveMongoRepository}
 * marker does not auto-scope derived finders).
 */
public interface ListingPhotoRepository
        extends TenantScopedReactiveMongoRepository<ListingPhoto, UUID> {

    /** All photos for a listing, oldest-first (stable order for captioning + the console). */
    Flux<ListingPhoto> findByTenantIdAndListingIdOrderByCreatedAtAsc(UUID tenantId, UUID listingId);
}
