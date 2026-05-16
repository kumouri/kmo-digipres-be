package com.kumouri.kmodigipresbe.repository.project;

import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MilestoneRepository extends TenantScopedReactiveMongoRepository<Milestone, UUID> {

    Flux<Milestone> findAllByTenantIdAndProjectIdOrderByOrderIndexAsc(UUID tenantId, UUID projectId);

    Mono<Milestone> findByTenantIdAndId(UUID tenantId, UUID id);
}
