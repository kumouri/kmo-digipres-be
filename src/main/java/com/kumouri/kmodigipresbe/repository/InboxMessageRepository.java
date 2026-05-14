package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface InboxMessageRepository
        extends TenantScopedReactiveMongoRepository<InboxMessage, UUID> {

    Flux<InboxMessage> findAllByTenantIdAndThreadIdOrderByReceivedAtAsc(UUID tenantId, UUID threadId);
}
