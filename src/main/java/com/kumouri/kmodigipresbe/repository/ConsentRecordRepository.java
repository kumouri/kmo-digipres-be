package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.compliance.ConsentRecord;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ConsentRecordRepository
        extends TenantScopedReactiveMongoRepository<ConsentRecord, UUID> {

    Flux<ConsentRecord> findAllByTenantIdAndContactIdOrderByRecordedAtDesc(
            UUID tenantId, UUID contactId);

    Mono<ConsentRecord> findFirstByTenantIdAndContactIdAndTopicOrderByRecordedAtDesc(
            UUID tenantId, UUID contactId, String topic);

    Mono<ConsentRecord> findByTenantIdAndId(UUID tenantId, UUID id);
}
