package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T5 — repository for the {@link CallbackOfferLog} per-CallSid offer-sent dedupe ledger.
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the subscriber
 * runs under a synthetic context). The unique {@code tenant_callsid_idx} is the concurrent backstop.
 */
public interface CallbackOfferLogRepository
        extends TenantScopedReactiveMongoRepository<CallbackOfferLog, UUID> {

    /** Whether an offer was already sent for this CallSid (the explicit-boolean probe source). */
    Mono<CallbackOfferLog> findByTenantIdAndCallSid(UUID tenantId, String callSid);
}
