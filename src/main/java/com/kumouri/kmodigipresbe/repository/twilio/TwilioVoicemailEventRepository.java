package com.kumouri.kmodigipresbe.repository.twilio;

import com.kumouri.kmodigipresbe.model.integration.TwilioVoicemailEvent;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the Twilio voicemail-callback idempotency ledger (Phase 1 — NMM
 * voicemail-to-lead).
 *
 * <p>{@link #findByTenantIdAndCallSid} is the <strong>explicit-boolean probe</strong>
 * used by {@code TwilioVoicemailService}: mapped to a boolean and branched
 * ({@code seen ? ack-200-no-op : processAndRecord}) —
 * <strong>NEVER {@code switchIfEmpty(process)}</strong> (the documented trap,
 * mandated grep target). The unique {@code tenant_callsid_idx} is the hard backstop
 * for concurrent re-delivery (mirrors {@code CalComWebhookEventRepository} /
 * {@code StripeWebhookEventRepository} exactly).
 *
 * <p>The derived finder includes an explicit {@code tenantId} predicate — a
 * {@code TenantScopedReactiveMongoRepository} does NOT auto-tenant-scope derived
 * finders.
 */
public interface TwilioVoicemailEventRepository
        extends TenantScopedReactiveMongoRepository<TwilioVoicemailEvent, UUID> {

    Mono<TwilioVoicemailEvent> findByTenantIdAndCallSid(UUID tenantId, String callSid);
}
