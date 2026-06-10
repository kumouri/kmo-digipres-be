package com.kumouri.kmodigipresbe.module.quoting.repository;

import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — repository for {@link QuoteRequest}.
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the public
 * intake/accept run under a synthetic widget context). The office-inbox list leans on the
 * {@code tenant_status_created_idx} compound index so the read is a pure DB sort.
 */
public interface QuoteRequestRepository
        extends TenantScopedReactiveMongoRepository<QuoteRequest, UUID> {

    /** Tenant-scoped single-quote load (the office detail + the accept guard; the not-found backstop). */
    Mono<QuoteRequest> findByTenantIdAndId(UUID tenantId, UUID id);

    /** The office inbox: all of a tenant's quotes, newest first. */
    Flux<QuoteRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** The office inbox filtered by status, newest first. */
    Flux<QuoteRequest> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, QuoteStatus status);
}
