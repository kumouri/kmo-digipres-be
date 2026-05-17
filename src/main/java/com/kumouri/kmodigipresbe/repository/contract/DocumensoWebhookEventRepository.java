package com.kumouri.kmodigipresbe.repository.contract;

import com.kumouri.kmodigipresbe.model.contract.DocumensoWebhookEvent;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the Documenso-webhook idempotency ledger (Phase F — F-D7).
 *
 * <p>{@link #findByTenantIdAndDocumensoEventId} is the <strong>explicit-boolean
 * probe</strong> used by {@code DocumensoWebhookService}: mapped to a boolean and
 * branched ({@code seen ? ack-200-no-op : processAndRecord}) — NEVER
 * {@code switchIfEmpty(process)} (mirrors the {@code StripeWebhookEventRepository}
 * pattern exactly). The unique {@code tenant_event_idx} is the hard backstop for
 * concurrent re-delivery.
 */
public interface DocumensoWebhookEventRepository
        extends TenantScopedReactiveMongoRepository<DocumensoWebhookEvent, UUID> {

    Mono<DocumensoWebhookEvent> findByTenantIdAndDocumensoEventId(
            UUID tenantId, String documensoEventId);
}
