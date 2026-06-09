package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T4 — repository for the per-tenant {@link SwitchboardConfig} (one row per tenant).
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the handlers run
 * under a synthetic context outside a request). {@code findByTenantId} is the find-or-create / upsert probe
 * — callers use an <strong>explicit-boolean</strong> branch, never {@code switchIfEmpty(create)}.
 */
public interface SwitchboardConfigRepository
        extends TenantScopedReactiveMongoRepository<SwitchboardConfig, UUID> {

    /** The config for this tenant, if any. */
    Mono<SwitchboardConfig> findByTenantId(UUID tenantId);
}
