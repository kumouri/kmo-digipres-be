package com.kumouri.kmodigipresbe.extension;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface FieldDefinitionRepository
        extends TenantScopedReactiveMongoRepository<FieldDefinition, UUID> {

    Flux<FieldDefinition> findAllByTenantIdAndEntityType(UUID tenantId, String entityType);
}
