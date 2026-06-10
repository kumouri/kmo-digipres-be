package com.kumouri.kmodigipresbe.module.realestate.listingprep.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link ListingPrepPack} (T10 — Listing Prep Studio).
 *
 * <p>{@link #findByTenantIdAndStatusOrderByCreatedAtDesc} backs the agent's review queue (the RE-4
 * {@code ListingMarketingDraftRepository} precedent); {@link #findByTenantIdAndId} is the tenant-scoped
 * single-row load for get/approve/skip ({@code 4460} on a miss). Derived finders carry an explicit
 * {@code tenantId} predicate (the {@link TenantScopedReactiveMongoRepository} marker does not auto-scope
 * derived finders).
 */
public interface ListingPrepPackRepository
        extends TenantScopedReactiveMongoRepository<ListingPrepPack, UUID> {

    /** The tenant's packs in a given status, most-recent first (the review queue). */
    Flux<ListingPrepPack> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, ListingPrepPack.Status status);

    /** All packs for one listing, most-recent first (the listing's prep history). */
    Flux<ListingPrepPack> findByTenantIdAndListingIdOrderByCreatedAtDesc(
            UUID tenantId, UUID listingId);

    Mono<ListingPrepPack> findByTenantIdAndId(UUID tenantId, UUID id);
}
