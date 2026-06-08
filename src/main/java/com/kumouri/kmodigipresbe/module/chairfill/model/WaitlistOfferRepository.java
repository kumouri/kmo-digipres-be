package com.kumouri.kmodigipresbe.module.chairfill.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
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

    /**
     * The stale-offer expiry-sweep query (CF-3 follow-up): this tenant's offers in {@code status} whose
     * {@code expiresAt} is strictly before {@code cutoff}. The {@code WaitlistOfferExpiryService} flips the
     * still-{@code OFFERED}, now-past ones to {@code EXPIRED} — pure ledger hygiene, since the
     * {@code WaitlistClaimService.resolveOpenOffer} claimability gate already excludes them. A null/absent
     * {@code expiresAt} is NOT matched (Mongo comparison type-bracketing on a Date operand), which mirrors
     * {@code resolveOpenOffer} treating a null expiry as never-expiring.
     */
    Flux<WaitlistOffer> findByTenantIdAndStatusAndExpiresAtBefore(
            UUID tenantId, WaitlistOffer.Status status, Instant cutoff);
}
