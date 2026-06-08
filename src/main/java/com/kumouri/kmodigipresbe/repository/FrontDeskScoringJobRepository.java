package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.module.frontdesk.model.FrontDeskScoringJob;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * FrontDesk IQ (FD-1) — the health analogue of {@link NoShowScoringJobRepository} (itself a mirror of
 * {@link LeadScoringJobRepository}). Lives in the top-level repository package alongside its chairfill
 * sibling for the same reason that one does: it is wired by the module auto-config but the repository
 * scan picks it up from the standard location.
 */
public interface FrontDeskScoringJobRepository
        extends TenantScopedReactiveMongoRepository<FrontDeskScoringJob, UUID> {

    Mono<FrontDeskScoringJob> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, FrontDeskScoringJob.JobStatus status);
}
