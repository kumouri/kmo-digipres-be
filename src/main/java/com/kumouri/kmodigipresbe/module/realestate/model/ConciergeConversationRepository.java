package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link ConciergeConversation} (RE-1).
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate (the inbound-SMS router runs outside a
 * request context under a synthetic {@code TenantContext}).
 */
public interface ConciergeConversationRepository
        extends TenantScopedReactiveMongoRepository<ConciergeConversation, UUID> {

    /** The thread for a (tenant, buyer phone, listing) — the primary find/create key. */
    Mono<ConciergeConversation> findByTenantIdAndBuyerPhoneAndListingId(
            UUID tenantId, String buyerPhone, UUID listingId);

    /** Most-recent conversation for a buyer phone within a tenant — the shared-number recency fallback. */
    Flux<ConciergeConversation> findByTenantIdAndBuyerPhoneOrderByLastInboundAtDesc(
            UUID tenantId, String buyerPhone);

    /** All conversations for a listing, most-recent first — the agent transcript list. */
    Flux<ConciergeConversation> findByTenantIdAndListingIdOrderByLastInboundAtDesc(
            UUID tenantId, UUID listingId);

    /**
     * RE-5a — all conversations for a tenant, most-recent first, backing the staff-facing concierge
     * list ({@code GET /realestate/conversations}). Additive read finder; carries the explicit
     * {@code tenantId} predicate (the marker does not auto-scope derived finders).
     */
    Flux<ConciergeConversation> findByTenantIdOrderByLastInboundAtDesc(UUID tenantId);

    /**
     * RE-5a — a single conversation scoped to the tenant, backing the detail read
     * ({@code GET /realestate/conversations/{id}}). Tenant-scoped so a thread can never be fetched for
     * a foreign tenant (the {@code ListingRepository.findByIdAndTenantId} posture; {@code 4270} on a
     * missing / not-owned conversation).
     */
    Mono<ConciergeConversation> findByIdAndTenantId(UUID id, UUID tenantId);
}
