package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T5 — repository for {@link CallbackRequest}.
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the handler runs
 * under a synthetic responder context). The ranked-queue finder leans on the
 * {@code tenant_status_score_idx} compound index so the revenue sort is a pure DB read.
 */
public interface CallbackRequestRepository
        extends TenantScopedReactiveMongoRepository<CallbackRequest, UUID> {

    /** The revenue-ranked open queue: a status's cards for this tenant, highest revenueScore first. */
    Flux<CallbackRequest> findByTenantIdAndStatusOrderByRevenueScoreDesc(UUID tenantId,
                                                                         CallbackStatus status);

    /** Idempotent correlation of an existing card for a voicemail CallSid (the handler dedupe). */
    Mono<CallbackRequest> findByTenantIdAndCallSid(UUID tenantId, String callSid);

    /**
     * The fallback dedupe for a reply with no prior voicemail (no callSid): the most-recent open
     * REQUESTED card for this caller phone — so a double-reply updates in place instead of duplicating.
     */
    Mono<CallbackRequest> findFirstByTenantIdAndFromPhoneAndStatusOrderByCreatedAtDesc(
            UUID tenantId, String fromPhone, CallbackStatus status);
}
