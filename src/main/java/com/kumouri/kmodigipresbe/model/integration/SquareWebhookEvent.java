package com.kumouri.kmodigipresbe.model.integration;

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
 * The Square-webhook idempotency ledger (security fix BE-14). Mirrors
 * {@link CalComWebhookEvent} / {@link com.kumouri.kmodigipresbe.model.billing.StripeWebhookEvent}
 * exactly in structure and rationale (the canonical webhook dedupe pattern) — Square was the
 * lone integration without one, so a replayed signed {@code payment.completed} could
 * double-record a payment.
 *
 * <p>One row per (tenant, Square {@code event_id}). The unique {@code tenant_event_idx} on
 * {@code (tenantId, squareEventId)} is the exactly-once guarantee: the webhook flow inserts
 * this row <strong>FIRST</strong> (before the {@code SquarePosService.syncSale}), so a
 * concurrent / replayed delivery's second insert hits a {@code DuplicateKeyException} and is
 * a 200 no-op with ZERO second effect.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (an infrastructure dedupe record,
 * not a CRM entity — the same rationale as the sibling ledgers).
 */
@Document("square_webhook_events")
@CompoundIndex(
        name = "tenant_event_idx",
        def = "{'tenantId':1,'squareEventId':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SquareWebhookEvent implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The Square event id (envelope {@code event_id}); part of the unique key. */
    private String squareEventId;

    /** The Square event type (e.g. {@code "payment.completed"}). */
    private String eventType;

    private Instant receivedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
