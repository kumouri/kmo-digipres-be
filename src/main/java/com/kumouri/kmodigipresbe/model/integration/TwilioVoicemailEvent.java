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
 * The Twilio voicemail-callback idempotency ledger (Phase 1 — NMM voicemail-to-lead).
 * Mirrors {@link CalComWebhookEvent} exactly in structure and rationale (the canonical
 * webhook idempotency pattern, ultimately {@code StripeWebhookEvent}).
 *
 * <p>One row per (tenant, Twilio {@code CallSid}). The unique {@code tenant_callsid_idx}
 * on {@code (tenantId, callSid)} is the exactly-once guarantee: the voicemail flow
 * inserts this row <strong>FIRST</strong> (before any Contact upsert, Activity creation,
 * or notify dispatch), so a concurrent re-delivery's second insert hits a
 * {@code DuplicateKeyException} and records ZERO second effect.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but
 * <strong>NOT {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong>
 * (the same rationale as {@code StripeWebhookEvent} / {@code CalComWebhookEvent} /
 * {@code DocumensoWebhookEvent} / {@code RecurringInvoiceOccurrence} — an
 * infrastructure dedupe record, not a CRM entity).
 */
@Document("twilio_voicemail_events")
@CompoundIndex(
        name = "tenant_callsid_idx",
        def = "{'tenantId':1,'callSid':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TwilioVoicemailEvent implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The Twilio {@code CallSid} (from the callback params); part of the unique key. */
    private String callSid;

    /** The Twilio {@code RecordingSid} of the voicemail recording (nullable). */
    private String recordingSid;

    /** The caller's phone number (Twilio {@code From}, E.164). */
    private String fromNumber;

    /** The dialed business number (Twilio {@code To}, E.164). */
    private String toNumber;

    /** The Twilio transcription status (e.g. {@code "completed"}, {@code "failed"}). */
    private String transcriptionStatus;

    /**
     * The CRM Contact that was found-or-created for the caller (nullable until/unless
     * the lead path runs).
     */
    private UUID resolvedContactId;

    /**
     * The CRM {@link com.kumouri.kmodigipresbe.model.activity.Activity} created for
     * this voicemail (nullable until/unless the lead path runs).
     */
    private UUID createdActivityId;

    /**
     * The field-service {@link com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder}
     * drafted from this voicemail (HS-1 — Home Services front desk). Non-null only for a
     * multi-trade home-services tenant whose voicemail produced a DRAFT WorkOrder AND on a server
     * where field-service is enabled; <strong>null for mole tenants</strong> (which create no
     * WorkOrder) and when the {@code WorkOrderService} bean is absent. No index change.
     */
    private UUID createdWorkOrderId;

    private Instant receivedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
