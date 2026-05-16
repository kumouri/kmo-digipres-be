package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantRepository extends ReactiveMongoRepository<Tenant, UUID> {
    Mono<Tenant> findBySlug(String slug);

    /**
     * Phase A2 — Zitadel org → tenant lookup. {@code zitadelOrgId} is sparse-unique
     * (see {@link Tenant#getZitadelOrgId()}), so at most one tenant matches. Hot path
     * (every Zitadel-mode request) — callers go through
     * {@link com.kumouri.kmodigipresbe.tenancy.ZitadelOrgTenantCache} rather than
     * hitting Mongo on each request.
     */
    Mono<Tenant> findByZitadelOrgId(String zitadelOrgId);
}
