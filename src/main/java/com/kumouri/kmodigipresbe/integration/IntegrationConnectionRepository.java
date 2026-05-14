package com.kumouri.kmodigipresbe.integration;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IntegrationConnectionRepository
        extends TenantScopedReactiveMongoRepository<IntegrationConnection, UUID> {

    Mono<IntegrationConnection> findByTenantIdAndProvider(UUID tenantId, String provider);
}
