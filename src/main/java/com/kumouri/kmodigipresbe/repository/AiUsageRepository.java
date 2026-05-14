package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.ai.AiUsage;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AiUsageRepository
        extends TenantScopedReactiveMongoRepository<AiUsage, UUID> {

    Mono<AiUsage> findByTenantIdAndYearMonth(UUID tenantId, String yearMonth);
}
