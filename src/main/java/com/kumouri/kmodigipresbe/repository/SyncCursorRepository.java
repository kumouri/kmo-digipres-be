package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.sync.SyncCursor;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SyncCursorRepository extends TenantScopedReactiveMongoRepository<SyncCursor, UUID> {

    Mono<SyncCursor> findByTenantIdAndUserIdAndCollection(UUID tenantId, UUID userId, String collection);
}
