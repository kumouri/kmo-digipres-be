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
 * The Cal.com-webhook idempotency ledger (Phase H — H.2 / H-D1). Mirrors
 * {@link com.kumouri.kmodigipresbe.model.billing.StripeWebhookEvent} exactly in
 * structure and rationale (the canonical webhook pattern).
 *
 * <p>One row per (tenant, Cal.com <strong>event</strong> id). The unique
 * {@code tenant_event_idx} on {@code (tenantId, calComEventId)} is the
 * exactly-once guarantee: the webhook flow inserts this row
 * <strong>FIRST</strong> (before any Meeting upsert or Activity creation), so a
 * concurrent re-delivery's second insert hits a {@code DuplicateKeyException} and
 * records ZERO second effect.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but
 * <strong>NOT {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong>
 * (the same rationale as {@code StripeWebhookEvent} / {@code DocumensoWebhookEvent}
 * / {@code RecurringInvoiceOccurrence} — an infrastructure dedupe record, not a
 * CRM entity).
 */
@Document("calcom_webhook_events")
@CompoundIndex(
        name = "tenant_event_idx",
        def = "{'tenantId':1,'calComEventId':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CalComWebhookEvent implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The Cal.com event id (from the webhook payload); part of the unique key. */
    private String calComEventId;

    /**
     * The Cal.com trigger event type (e.g. {@code "BOOKING_CREATED"},
     * {@code "BOOKING_CANCELLED"}).
     */
    private String eventType;

    /**
     * The Cal.com booking uid — the projection key correlating this event to a
     * {@link com.kumouri.kmodigipresbe.model.meeting.Meeting} row (nullable for
     * OTHER events).
     */
    private String calComBookingUid;

    /**
     * The CRM {@link com.kumouri.kmodigipresbe.model.meeting.Meeting} that was
     * upserted/cancelled for this event (nullable until/unless the reconcile runs).
     */
    private UUID meetingId;

    /**
     * The CRM Contact that was resolved for this booking's attendee (nullable if
     * unresolved — error code 3903 advisory skip path).
     */
    private UUID resolvedContactId;

    private Instant receivedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
