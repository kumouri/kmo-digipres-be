package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CompanyRepository extends TenantScopedReactiveMongoRepository<Company, UUID> {

    /**
     * Explicit-tenant single-load (the {@code ContactRepository}/{@code ProjectRepository}
     * precedent). Carries the {@code tenantId} predicate at the query level —
     * {@code TenantScopedReactiveMongoRepository} auto-scopes only base-repo methods, not
     * derived finders. Used by the Phase-J contractor client view.
     */
    Mono<Company> findByTenantIdAndId(UUID tenantId, UUID id);
}
