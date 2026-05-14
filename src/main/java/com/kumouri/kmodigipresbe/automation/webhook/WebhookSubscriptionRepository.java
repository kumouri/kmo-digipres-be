package com.kumouri.kmodigipresbe.automation.webhook;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface WebhookSubscriptionRepository
        extends TenantScopedReactiveMongoRepository<WebhookSubscription, UUID> {

    Flux<WebhookSubscription> findAllByTenantIdAndActive(UUID tenantId, boolean active);
}
