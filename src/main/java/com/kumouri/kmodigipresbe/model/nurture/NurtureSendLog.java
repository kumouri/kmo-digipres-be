package com.kumouri.kmodigipresbe.model.nurture;

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
 * The per-(enrollment, step) send idempotency ledger (E1 — Nurture / Cadence Engine). One row per
 * cadence touch. The unique {@code tenant_enrollment_step_idx} compound index is the exactly-once
 * guarantee: the {@code NurtureRunner} inserts this row <strong>FIRST</strong> (before any SMS/email
 * send), so a restart / re-run / concurrent tick loses on a {@code DuplicateKeyException} and produces
 * ZERO duplicate touch — the money-grade {@code RecurringInvoiceOccurrence} / {@code CoverageNudgeLog}
 * ledger-insert-FIRST pattern, here for a (non-money) outbound cadence message.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same rationale as
 * {@code RecurringInvoiceOccurrence} / {@code CoverageNudgeLog} / {@code StripeWebhookEvent}: an
 * infrastructure dedupe record, not a CRM entity — it produces no audit events). The
 * {@code (tenantId, contactId, sentAt)} rows also back the TCPA rolling frequency-cap count.
 */
@Document("nurture_send_logs")
@CompoundIndex(
        name = "tenant_enrollment_step_idx",
        def = "{ 'tenantId': 1, 'enrollmentId': 1, 'stepIndex': 1 }",
        unique = true)
@CompoundIndex(
        name = "tenant_contact_sent_idx",
        def = "{ 'tenantId': 1, 'contactId': 1, 'sentAt': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NurtureSendLog implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The {@link NurtureEnrollment} id; part of the unique key. */
    private UUID enrollmentId;

    /** The cadence step index that was sent; part of the unique key. */
    private int stepIndex;

    private NurtureChannel channel;

    /** The Contact the touch was sent to (also backs the per-contact frequency-cap count). */
    private UUID contactId;

    /** True if the AI rewrite was applied (false ⇒ the templated copy was sent — e.g. budget/upstream fail). */
    private boolean aiPersonalizedApplied;

    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
