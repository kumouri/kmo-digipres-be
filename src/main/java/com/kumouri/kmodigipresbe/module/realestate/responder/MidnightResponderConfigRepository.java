package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — repository for {@link MidnightResponderConfig}.
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders. One row per
 * tenant (unique {@code tenant_idx}); {@link #findByTenantId} is the single-row anchor for the config
 * read/upsert, the tier-router's mapping lookup, and the latency-stats after-hours window.
 */
public interface MidnightResponderConfigRepository
        extends TenantScopedReactiveMongoRepository<MidnightResponderConfig, UUID> {

    Mono<MidnightResponderConfig> findByTenantId(UUID tenantId);
}
