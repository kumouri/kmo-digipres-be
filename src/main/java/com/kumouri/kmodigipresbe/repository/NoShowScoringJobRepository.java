package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowScoringJob;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * ChairFill (CF-1) verbatim mirror of
 * {@link com.kumouri.kmodigipresbe.repository.LeadScoringJobRepository}.
 */
public interface NoShowScoringJobRepository
        extends TenantScopedReactiveMongoRepository<NoShowScoringJob, UUID> {

    Mono<NoShowScoringJob> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, NoShowScoringJob.JobStatus status);
}
