package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface DealRepository extends TenantScopedReactiveMongoRepository<Deal, UUID> {
    Flux<Deal> findAllByTenantIdAndStage(UUID tenantId, PipelineStage stage);

    Mono<Boolean> existsByTenantIdAndPrimaryContactIdAndStageAndUpdatedAtAfter(
            UUID tenantId, UUID primaryContactId, PipelineStage stage, Instant since);
}
