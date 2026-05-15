package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface EmailEngagementRepository
        extends TenantScopedReactiveMongoRepository<EmailEngagement, UUID> {

    Flux<EmailEngagement> findAllByTenantId(UUID tenantId);

    Flux<EmailEngagement> findAllByTenantIdAndContactIdOrderByEventAtDesc(
            UUID tenantId, UUID contactId);

    Flux<EmailEngagement> findAllByTenantIdAndMessageIdOrderByEventAtDesc(
            UUID tenantId, String messageId);
}
