package com.kumouri.kmodigipresbe.repository.project;

import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Explicit-tenant finders per the Ticket-repo precedent. Auto-scoped base-repo
 * methods ({@code findById}, {@code save}, etc.) apply the {@code tenantId} predicate
 * automatically via {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository};
 * derived finders pass {@code tenantId} explicitly for index-friendliness (C-D2).
 */
public interface ProjectRepository extends TenantScopedReactiveMongoRepository<Project, UUID> {

    Flux<Project> findAllByTenantId(UUID tenantId);

    Mono<Project> findByTenantIdAndId(UUID tenantId, UUID id);

    Mono<Project> findByTenantIdAndCode(UUID tenantId, String code);

    Mono<Project> findFirstByTenantIdAndDealId(UUID tenantId, UUID dealId);

    Mono<Boolean> existsByTenantIdAndDealId(UUID tenantId, UUID dealId);
}
