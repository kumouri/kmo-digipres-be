package com.kumouri.kmodigipresbe.module.stylermatch.repository;

import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — repository for {@link StylerMatch}.
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the public
 * intake/accept run under a synthetic widget context). The office-inbox + analytics reads lean on the
 * {@code tenant_status_created_idx} compound index. Mirrors the T9 {@code StyleConsultRepository}.
 */
public interface StylerMatchRepository
        extends TenantScopedReactiveMongoRepository<StylerMatch, UUID> {

    /** Tenant-scoped single-match load (the office detail + the accept guard; the not-found backstop). */
    Mono<StylerMatch> findByTenantIdAndId(UUID tenantId, UUID id);

    /** The office inbox: all of a tenant's matches, newest first. */
    Flux<StylerMatch> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** All of a tenant's matches (the analytics funnel scan); explicit tenant predicate. */
    Flux<StylerMatch> findByTenantId(UUID tenantId);
}
