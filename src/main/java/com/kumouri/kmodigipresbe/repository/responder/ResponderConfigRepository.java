package com.kumouri.kmodigipresbe.repository.responder;

import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the per-tenant {@link ResponderConfig}. One row per tenant (unique {@code tenant_idx}).
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the router
 * runs OUTSIDE a request context (a synthetic {@code TenantContext} per inbound webhook).
 */
public interface ResponderConfigRepository
        extends TenantScopedReactiveMongoRepository<ResponderConfig, UUID> {

    /** The tenant's responder config (empty ⇒ the router is a no-op = {@code IGNORED}). */
    Mono<ResponderConfig> findByTenantId(UUID tenantId);
}
