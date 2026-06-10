package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — repository for the per-tenant {@link QuoteCloserConfig} (one row per tenant,
 * unique {@code tenant_idx}). The derived finder carries an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the enrollment
 * job + the accept subscriber both run OUTSIDE a request context under a synthetic {@code TenantContext}).
 */
public interface QuoteCloserConfigRepository
        extends TenantScopedReactiveMongoRepository<QuoteCloserConfig, UUID> {

    Mono<QuoteCloserConfig> findByTenantId(UUID tenantId);
}
