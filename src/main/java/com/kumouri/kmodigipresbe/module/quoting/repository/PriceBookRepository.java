package com.kumouri.kmodigipresbe.module.quoting.repository;

import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — repository for the per-tenant {@link PriceBook}. One row per
 * tenant (unique {@code tenant_idx}). The derived finder carries an explicit {@code tenantId}
 * predicate (the {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived
 * finders; the public intake runs under a synthetic widget context).
 */
public interface PriceBookRepository
        extends TenantScopedReactiveMongoRepository<PriceBook, UUID> {

    /** The tenant's price book (empty ⇒ the tenant hasn't seeded one yet → 4431 on a read). */
    Mono<PriceBook> findByTenantId(UUID tenantId);
}
