package com.kumouri.kmodigipresbe.automation.webhook;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WebhookSubscriptionRepository
        extends TenantScopedReactiveMongoRepository<WebhookSubscription, UUID> {

    Flux<WebhookSubscription> findAllByTenantIdAndActive(UUID tenantId, boolean active);

    /**
     * Additive finder (Phase H — H.5 / H-D5): finds the first active or inactive
     * subscription matching the given tenant, target URL, and a specific event-type
     * string within the {@code eventTypes} list.  Used by
     * {@link com.kumouri.kmodigipresbe.integration.activepieces.ActivepiecesSubscriptionSeeder}
     * for the explicit-boolean idempotency probe per (tenantId, url, eventType).
     */
    Mono<WebhookSubscription> findFirstByTenantIdAndUrlAndEventTypesContaining(
            UUID tenantId, String url, String eventType);
}
