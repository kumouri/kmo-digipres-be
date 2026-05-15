package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface InboxMessageRepository
        extends TenantScopedReactiveMongoRepository<InboxMessage, UUID> {

    Flux<InboxMessage> findAllByTenantIdAndThreadIdOrderByReceivedAtAsc(UUID tenantId, UUID threadId);

    Flux<InboxMessage> findAllByTenantIdAndContactId(UUID tenantId, UUID contactId);

    Flux<InboxMessage> findAllByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    Mono<Void> deleteByTenantIdAndId(UUID tenantId, UUID id);
}
