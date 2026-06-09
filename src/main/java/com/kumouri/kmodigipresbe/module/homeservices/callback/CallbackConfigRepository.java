package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T5 — repository for the per-tenant {@link CallbackConfig} copy book. One row per tenant (unique
 * {@code tenant_idx}). The derived finder carries an explicit {@code tenantId} predicate.
 */
public interface CallbackConfigRepository
        extends TenantScopedReactiveMongoRepository<CallbackConfig, UUID> {

    /** The tenant's callback config (empty ⇒ the defaults in {@link CallbackCopy} apply). */
    Mono<CallbackConfig> findByTenantId(UUID tenantId);
}
