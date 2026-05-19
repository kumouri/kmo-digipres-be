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

    /**
     * IMAP-poller idempotency probe (Phase H.3). Returns the first message with
     * the given RFC-822 Message-ID for a tenant; used with
     * {@code .map(x -> true).defaultIfEmpty(false)} to build an explicit-boolean
     * skip branch — never {@code switchIfEmpty(ingest)}.
     */
    Mono<InboxMessage> findFirstByTenantIdAndMessageId(UUID tenantId, String messageId);

    Flux<InboxMessage> findAllByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    Mono<Void> deleteByTenantIdAndId(UUID tenantId, UUID id);
}
