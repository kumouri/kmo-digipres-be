package com.kumouri.kmodigipresbe.model.billing;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * The Stripe-webhook idempotency ledger (Phase E — E-D7 — closes the biggest
 * pre-existing money gap: today's {@code StripeWebhookService} double-records a
 * Payment on Stripe re-delivery).
 *
 * <p>One row per (tenant, Stripe <strong>event</strong> id). The unique
 * {@code tenant_event_idx} is the exactly-once guarantee: the webhook flow inserts
 * this row <strong>FIRST</strong> (before {@code InvoiceService.recordPayment}), so
 * a concurrent re-delivery's second insert hits {@code DuplicateKeyException} and
 * records ZERO second Payment. The dedupe key is the <strong>event</strong> id, NOT
 * the payment-intent id (one intent → many events).
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but
 * <strong>NOT {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same
 * rationale as {@code IdempotencyKey} / {@code RecurringInvoiceOccurrence}).
 */
@Document("stripe_webhook_events")
@CompoundIndex(
        name = "tenant_event_idx",
        def = "{'tenantId':1,'stripeEventId':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StripeWebhookEvent implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The Stripe {@code event.id} (e.g. {@code evt_...}); part of the unique key. */
    private String stripeEventId;

    /** The Stripe {@code event.type} (e.g. {@code payment_intent.succeeded}). */
    private String eventType;

    /** The CRM Invoice the event was correlated to (nullable for ignored events). */
    private UUID invoiceId;

    /** The Payment recorded for this event (nullable until/unless one is created). */
    private UUID paymentId;

    private Instant receivedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
