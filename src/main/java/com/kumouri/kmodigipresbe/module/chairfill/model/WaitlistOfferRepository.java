package com.kumouri.kmodigipresbe.module.chairfill.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Repository for the CF-3 {@link WaitlistOffer} ledger.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and both the
 * {@code GapFillService} (offer mint) and the inbound-SMS correlation run OUTSIDE a request context (a
 * synthetic {@code TenantContext} per event / webhook).
 */
public interface WaitlistOfferRepository
        extends TenantScopedReactiveMongoRepository<WaitlistOffer, UUID> {

    /**
     * The inbound-YES correlation query: the offers for this tenant + phone in a given status,
     * most-recent first. The {@code WaitlistClaimService} takes the first still-{@code OFFERED},
     * un-expired one.
     */
    Flux<WaitlistOffer> findByTenantIdAndContactPhoneAndStatusOrderBySentAtDesc(
            UUID tenantId, String contactPhone, WaitlistOffer.Status status);

    /** Sibling offers for the same freed slot — used to mark the rest SUPERSEDED after a claim. */
    Flux<WaitlistOffer> findByTenantIdAndFreedBookingId(UUID tenantId, UUID freedBookingId);

    /**
     * Recent offers for a tenant, newest sent first — the CF-5a waitlist-board read
     * ({@code WaitlistBoardController}) shows who's been offered what, across all statuses
     * (OFFERED/CLAIMED/SUPERSEDED/EXPIRED). The controller caps the stream. Additive.
     */
    Flux<WaitlistOffer> findByTenantIdOrderBySentAtDesc(UUID tenantId);
}
