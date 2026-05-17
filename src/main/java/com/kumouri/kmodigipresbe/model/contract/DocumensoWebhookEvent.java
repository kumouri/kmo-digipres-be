package com.kumouri.kmodigipresbe.model.contract;

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
 * The Documenso-webhook idempotency ledger (Phase F — F-D7). Mirrors
 * {@link com.kumouri.kmodigipresbe.model.billing.StripeWebhookEvent} exactly in
 * structure and rationale.
 *
 * <p>One row per (tenant, Documenso <strong>event</strong> id). The unique
 * {@code tenant_event_idx} is the exactly-once guarantee: the webhook flow inserts
 * this row <strong>FIRST</strong> (before any side effect — signed-PDF store,
 * Deal→WON promotion), so a concurrent re-delivery's second insert hits a
 * {@code DuplicateKeyException} and records ZERO second effect.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but
 * <strong>NOT {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same
 * rationale as {@code StripeWebhookEvent} / {@code RecurringInvoiceOccurrence} /
 * {@code IdempotencyKey} — an infrastructure dedupe record, not a CRM entity).
 */
@Document("documenso_webhook_events")
@CompoundIndex(
        name = "tenant_event_idx",
        def = "{'tenantId':1,'documensoEventId':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DocumensoWebhookEvent implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The Documenso event id (from the webhook payload); part of the unique key. */
    private String documensoEventId;

    /** The adapter-mapped event type (e.g. {@code "DOCUMENT_SIGNED"}, {@code "OTHER"}). */
    private String eventType;

    /** The CRM {@link Contract} the event was correlated to (nullable for OTHER events). */
    private UUID contractId;

    /**
     * S3 storage ref of the signed PDF stored for this event (nullable — set only
     * on DOCUMENT_SIGNED; null for OTHER events or if the store failed).
     */
    private String signedPdfStorageRef;

    private Instant receivedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
