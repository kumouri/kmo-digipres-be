package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.compliance.RetentionPolicy;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RetentionPolicyRepository
        extends TenantScopedReactiveMongoRepository<RetentionPolicy, UUID> {

    Flux<RetentionPolicy> findAllByTenantId(UUID tenantId);

    Mono<RetentionPolicy> findByTenantIdAndEntityType(UUID tenantId, String entityType);
}
