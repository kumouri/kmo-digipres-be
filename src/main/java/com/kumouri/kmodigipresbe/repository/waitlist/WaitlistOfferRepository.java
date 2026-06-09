package com.kumouri.kmodigipresbe.repository.waitlist;

import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * E4 — repository for the generic {@link WaitlistOffer} ledger.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and both the
 * {@code GapFillEngine} (offer mint) and the inbound-YES correlation run OUTSIDE a request context (a
 * synthetic {@code TenantContext}).
 */
public interface WaitlistOfferRepository
        extends TenantScopedReactiveMongoRepository<WaitlistOffer, UUID> {

    /**
     * The inbound-YES correlation query: the offers for this tenant + phone in a given status,
     * most-recent first. {@code WaitlistClaimEngine} takes the first still-{@code OFFERED}, un-expired one.
     */
    Flux<WaitlistOffer> findByTenantIdAndContactPhoneAndStatusOrderBySentAtDesc(
            UUID tenantId, String contactPhone, WaitlistOffer.Status status);

    /** Sibling offers for the same freed slot — used to mark the rest SUPERSEDED after a claim. */
    Flux<WaitlistOffer> findByTenantIdAndSlotKey(UUID tenantId, String slotKey);

    /** Recent offers for a tenant, newest sent first — the admin board read (caps in the controller). */
    Flux<WaitlistOffer> findByTenantIdOrderBySentAtDesc(UUID tenantId);

    /**
     * The stale-offer expiry-sweep query: this tenant's offers in {@code status} whose {@code expiresAt} is
     * strictly before {@code cutoff}. {@code WaitlistOfferExpiryService} flips the still-{@code OFFERED},
     * now-past ones to {@code EXPIRED} — pure ledger hygiene (the claimability gate already excludes them).
     * A null/absent {@code expiresAt} is NOT matched (Mongo comparison type-bracketing on a Date operand),
     * mirroring the claim path treating a null expiry as never-expiring.
     */
    Flux<WaitlistOffer> findByTenantIdAndStatusAndExpiresAtBefore(
            UUID tenantId, WaitlistOffer.Status status, Instant cutoff);
}
