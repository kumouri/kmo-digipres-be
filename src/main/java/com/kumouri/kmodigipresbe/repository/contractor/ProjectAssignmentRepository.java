package com.kumouri.kmodigipresbe.repository.contractor;

import com.kumouri.kmodigipresbe.model.contractor.ProjectAssignment;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Explicit-tenant finders per the Phase-C {@code ProjectRepository} precedent — the
 * {@link TenantScopedReactiveMongoRepository} marker auto-scopes only base-repo methods
 * ({@code findById}, {@code save}), NOT derived finders, which pass {@code tenantId}
 * explicitly for index-friendliness.
 */
public interface ProjectAssignmentRepository
        extends TenantScopedReactiveMongoRepository<ProjectAssignment, UUID> {

    Mono<ProjectAssignment> findByTenantIdAndId(UUID tenantId, UUID id);

    Flux<ProjectAssignment> findAllByTenantIdAndProjectId(UUID tenantId, UUID projectId);

    Flux<ProjectAssignment> findAllByTenantIdAndUserId(UUID tenantId, UUID userId);

    /** Contractor "my assigned projects" — active assignments only (Phase J scoping). */
    Flux<ProjectAssignment> findAllByTenantIdAndUserIdAndActiveTrue(UUID tenantId, UUID userId);

    /**
     * The rate-resolution + ownership + assignment-idempotency lookup for a
     * (project, user) pair. At most one row (unique {@code tenant_project_user_idx}).
     */
    Mono<ProjectAssignment> findFirstByTenantIdAndProjectIdAndUserId(
            UUID tenantId, UUID projectId, UUID userId);
}
