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
}
