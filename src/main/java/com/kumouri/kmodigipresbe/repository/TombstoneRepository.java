package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.sync.Tombstone;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface TombstoneRepository extends TenantScopedReactiveMongoRepository<Tombstone, UUID> {

    Flux<Tombstone> findByTenantIdAndCollectionAndDeletedAtAfter(UUID tenantId, String collection, Instant deletedAt);
}
