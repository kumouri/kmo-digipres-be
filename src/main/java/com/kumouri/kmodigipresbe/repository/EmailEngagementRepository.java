package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagementEvent;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface EmailEngagementRepository
        extends TenantScopedReactiveMongoRepository<EmailEngagement, UUID> {

    Flux<EmailEngagement> findAllByTenantId(UUID tenantId);

    Flux<EmailEngagement> findAllByTenantIdAndContactIdOrderByEventAtDesc(
            UUID tenantId, UUID contactId);

    Flux<EmailEngagement> findAllByTenantIdAndMessageIdOrderByEventAtDesc(
            UUID tenantId, String messageId);

    /**
     * Idempotency probe for bounce/spam routing (Phase H.4). Returns the first
     * recorded engagement matching (tenantId, messageId, event) so callers can
     * apply an explicit-boolean skip instead of a {@code switchIfEmpty(process)}
     * pattern — per §9 of the Phase-H plan.
     */
    Mono<EmailEngagement> findFirstByTenantIdAndMessageIdAndEvent(
            UUID tenantId, String messageId, EmailEngagementEvent event);
}
