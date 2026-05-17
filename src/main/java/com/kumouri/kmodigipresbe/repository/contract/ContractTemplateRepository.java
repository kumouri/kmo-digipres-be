package com.kumouri.kmodigipresbe.repository.contract;

import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link ContractTemplate} (Phase F — F-D2).
 */
public interface ContractTemplateRepository
        extends TenantScopedReactiveMongoRepository<ContractTemplate, UUID> {

    Mono<ContractTemplate> findByTenantIdAndId(UUID tenantId, UUID id);

    Flux<ContractTemplate> findAllByTenantId(UUID tenantId);

    /**
     * Load an <em>active</em> template by id; used by the Quote-ACCEPTED→SOW
     * flow to refuse inactive templates (errorCode 3705).
     */
    Mono<ContractTemplate> findByTenantIdAndIdAndActiveTrue(UUID tenantId, UUID id);
}
