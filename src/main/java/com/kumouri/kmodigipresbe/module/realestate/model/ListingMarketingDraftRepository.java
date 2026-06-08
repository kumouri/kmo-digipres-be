package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link ListingMarketingDraft} (RE-4 — Marketing Studio).
 *
 * <p>{@link #findByTenantIdAndStatusOrderByCreatedAtDesc} backs the agent's review queue (the GBP
 * {@code findByTenantIdAndStatusOrderByReceivedAtDesc} precedent); {@link #findByTenantIdAndId} is the
 * tenant-scoped single-row load for approve/skip ({@code 4253} on a miss). Derived finders carry an
 * explicit {@code tenantId} predicate (the {@link TenantScopedReactiveMongoRepository} marker does not
 * auto-scope derived finders).
 */
public interface ListingMarketingDraftRepository
        extends TenantScopedReactiveMongoRepository<ListingMarketingDraft, UUID> {

    /** The tenant's drafts in a given status, most-recent first (the review queue / history). */
    Flux<ListingMarketingDraft> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, ListingMarketingDraft.Status status);

    /** All drafts for one listing, most-recent first (the listing's marketing history). */
    Flux<ListingMarketingDraft> findByTenantIdAndListingIdOrderByCreatedAtDesc(
            UUID tenantId, UUID listingId);

    Mono<ListingMarketingDraft> findByTenantIdAndId(UUID tenantId, UUID id);
}
