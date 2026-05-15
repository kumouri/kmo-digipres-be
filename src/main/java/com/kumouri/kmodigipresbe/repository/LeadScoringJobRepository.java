package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.ai.LeadScoringJob;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LeadScoringJobRepository extends TenantScopedReactiveMongoRepository<LeadScoringJob, UUID> {
    Mono<LeadScoringJob> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, LeadScoringJob.JobStatus status);
}
