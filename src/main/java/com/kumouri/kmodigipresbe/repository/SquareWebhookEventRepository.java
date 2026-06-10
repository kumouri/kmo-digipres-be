package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.integration.SquareWebhookEvent;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the Square-webhook idempotency ledger (security fix BE-14).
 *
 * <p>{@link #findByTenantIdAndSquareEventId} is the <strong>explicit-boolean probe</strong>
 * used by {@code SquareWebhookService}: mapped to a boolean and branched
 * ({@code seen ? ack-200-no-op : processAndRecord}) — <strong>NEVER
 * {@code switchIfEmpty(process)}</strong>. The unique {@code tenant_event_idx} is the hard
 * backstop for concurrent re-delivery (mirrors {@code CalComWebhookEventRepository} /
 * {@code StripeWebhookEventRepository} exactly).
 */
public interface SquareWebhookEventRepository
        extends TenantScopedReactiveMongoRepository<SquareWebhookEvent, UUID> {

    Mono<SquareWebhookEvent> findByTenantIdAndSquareEventId(UUID tenantId, String squareEventId);
}
