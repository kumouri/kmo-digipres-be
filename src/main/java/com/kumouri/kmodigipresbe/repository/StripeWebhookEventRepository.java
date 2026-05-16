package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.billing.StripeWebhookEvent;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the Stripe-webhook idempotency ledger (E-D7).
 *
 * <p>{@link #findByTenantIdAndStripeEventId} is the <strong>explicit-boolean
 * probe</strong> used by {@code StripeWebhookService}: mapped to a boolean and
 * branched ({@code seen ? ack-200-no-op : processAndRecord}) — NEVER
 * {@code switchIfEmpty(process)} (the documented trap). The unique
 * {@code tenant_event_idx} is the hard backstop for concurrent re-delivery.
 */
public interface StripeWebhookEventRepository
        extends TenantScopedReactiveMongoRepository<StripeWebhookEvent, UUID> {

    Mono<StripeWebhookEvent> findByTenantIdAndStripeEventId(UUID tenantId, String stripeEventId);
}
