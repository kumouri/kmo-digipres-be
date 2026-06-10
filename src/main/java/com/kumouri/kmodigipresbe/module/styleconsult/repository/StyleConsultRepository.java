package com.kumouri.kmodigipresbe.module.styleconsult.repository;

import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — repository for {@link StyleConsult}.
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the public
 * intake/accept run under a synthetic widget context). The office-inbox + analytics reads lean on the
 * {@code tenant_status_created_idx} compound index.
 */
public interface StyleConsultRepository
        extends TenantScopedReactiveMongoRepository<StyleConsult, UUID> {

    /** Tenant-scoped single-consult load (the office detail + the accept guard; the not-found backstop). */
    Mono<StyleConsult> findByTenantIdAndId(UUID tenantId, UUID id);

    /** The office inbox: all of a tenant's consults, newest first. */
    Flux<StyleConsult> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** All of a tenant's consults (the analytics funnel scan); explicit tenant predicate. */
    Flux<StyleConsult> findByTenantId(UUID tenantId);
}
