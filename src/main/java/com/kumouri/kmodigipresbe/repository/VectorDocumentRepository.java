package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.ai.VectorDocument;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface VectorDocumentRepository
        extends TenantScopedReactiveMongoRepository<VectorDocument, UUID> {

    Mono<VectorDocument> findByTenantIdAndSourceTypeAndSourceId(
            UUID tenantId, String sourceType, UUID sourceId);
}
