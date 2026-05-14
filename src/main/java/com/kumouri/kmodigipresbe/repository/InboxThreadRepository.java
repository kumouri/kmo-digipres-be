package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.inbox.InboxThread;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface InboxThreadRepository
        extends TenantScopedReactiveMongoRepository<InboxThread, UUID> {

    Flux<InboxThread> findAllByTenantIdOrderByLastMessageAtDesc(UUID tenantId);

    Mono<InboxThread> findByTenantIdAndFromAddressAndSubjectNormalized(
            UUID tenantId, String fromAddress, String subjectNormalized);
}
