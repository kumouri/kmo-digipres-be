package com.kumouri.kmodigipresbe.repository.calcom;

import com.kumouri.kmodigipresbe.model.integration.CalComWebhookEvent;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the Cal.com-webhook idempotency ledger (Phase H — H.2 / H-D1).
 *
 * <p>{@link #findByTenantIdAndCalComEventId} is the <strong>explicit-boolean
 * probe</strong> used by {@code CalComWebhookService}: mapped to a boolean and
 * branched ({@code seen ? ack-200-no-op : processAndRecord}) —
 * <strong>NEVER {@code switchIfEmpty(process)}</strong> (the documented trap,
 * mandated grep target). The unique {@code tenant_event_idx} is the hard backstop
 * for concurrent re-delivery (mirrors {@code StripeWebhookEventRepository} /
 * {@code DocumensoWebhookEventRepository} exactly).
 */
public interface CalComWebhookEventRepository
        extends TenantScopedReactiveMongoRepository<CalComWebhookEvent, UUID> {

    Mono<CalComWebhookEvent> findByTenantIdAndCalComEventId(UUID tenantId, String calComEventId);
}
