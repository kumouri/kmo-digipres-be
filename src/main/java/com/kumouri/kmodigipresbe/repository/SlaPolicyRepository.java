package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.servicehub.SlaPolicy;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SlaPolicyRepository extends TenantScopedReactiveMongoRepository<SlaPolicy, UUID> {

    Flux<SlaPolicy> findAllByTenantId(UUID tenantId);

    Mono<SlaPolicy> findByTenantIdAndId(UUID tenantId, UUID id);
}
