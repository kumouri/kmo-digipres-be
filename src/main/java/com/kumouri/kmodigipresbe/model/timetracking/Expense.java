package com.kumouri.kmodigipresbe.model.timetracking;

import com.kumouri.kmodigipresbe.audit.Auditable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records a reimbursable/billable business expense (Phase D — D-D1, D-D2, D-D8).
 *
 * <h2>Approval workflow (D-D10)</h2>
 * Lifecycle: {@code PENDING → APPROVED | REJECTED} via
 * {@code POST /expenses/{id}/approve} and {@code POST /expenses/{id}/reject?reason=}.
 * Approval/rejection requires {@code ADMIN} role ({@link com.kumouri.kmodigipresbe.tenancy.RoleGuard}).
 * Only {@code APPROVED + billable + UNBILLED} expenses are eligible for invoicing (D-D8).
 *
 * <h2>Receipt attachment (D-D8, D-D12)</h2>
 * Receipts flow through the existing {@code /attachments} presign → S3 PUT → register
 * pipeline with {@code subjectType="EXPENSE", subjectId=<expenseId>}. Zero new file
 * code — {@code Attachment.subjectType} is a free-form String (verified
 * {@code Attachment.java:42-46}). {@link #receiptAttachmentId} is a denormalized
 * convenience pointer; the canonical link is the {@code ("EXPENSE", expenseId)} subject
 * pair queryable via {@code GET /attachments}.
 *
 * <h2>Invoice billing (D-D8)</h2>
 * {@link #invoicedInvoiceId} is the idempotency anchor (non-null iff INVOICED),
 * symmetric with {@code Milestone.spawnedInvoiceId} and {@code TimeEntry.invoicedInvoiceId}.
 */
@Document("expenses")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_user_incurred_idx", def = "{'tenantId':1,'userId':1,'incurredOn':-1}"),
        @CompoundIndex(name = "tenant_project_idx",       def = "{'tenantId':1,'projectId':1}"),
        @CompoundIndex(name = "tenant_status_idx",        def = "{'tenantId':1,'approvalStatus':1}"),
        @CompoundIndex(name = "tenant_billing_idx",       def = "{'tenantId':1,'billingStatus':1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Expense implements Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /**
     * Submitter. Required — service validates (errorCode 3511 if null).
     * Set from {@code ctx.userId()} on create unless explicitly supplied.
     */
    private UUID userId;

    /** Nullable FK to Phase-C {@code Project}. */
    private UUID projectId;

    /** Nullable FK to Phase-C {@code Task}. */
    private UUID taskId;

    /**
     * Required (errorCode 3512 if blank). Free-text description of the expense.
     */
    private String description;

    /**
     * Nullable; free-form category (TRAVEL/MEALS/SOFTWARE/…). String rather than
     * enum to avoid premature taxonomy — consistent with {@code Attachment.subjectType}
     * being free-form (D-D2).
     */
    private String category;

    /**
     * Required, must be &gt; 0 (errorCode 3513 if null or ≤ 0). The raw incurred cost.
     */
    private BigDecimal amount;

    @Builder.Default
    private String currency = "USD";

    /**
     * Required (errorCode 3514 if null). The date the expense was incurred
     * — used for display, sorting, and period attribution.
     */
    private LocalDate incurredOn;

    /**
     * Nullable per-expense markup applied when billed (e.g. {@code 15} ⇒ +15%).
     * Null ⇒ the invoice-from-expenses caller's {@code defaultMarkupPercent} is used
     * (or {@code BigDecimal.ZERO} if none supplied). D-D8.
     */
    private BigDecimal markupPercent;

    @Builder.Default
    private boolean billable = true;

    /**
     * Approval workflow status. Default {@code PENDING}.
     * Only {@code APPROVED + billable + UNBILLED} expenses are eligible for invoicing.
     */
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    /** Nullable; set on approve or reject — who made the decision. */
    private UUID approvedByUserId;

    /** Nullable; set on approve or reject — when the decision was made. */
    private Instant decidedAt;

    /**
     * Nullable; required when transitioning → {@code REJECTED} (errorCode 3516).
     */
    private String rejectionReason;

    /**
     * Invoice billing state. Default {@code UNBILLED}.
     * The idempotency dimension for invoice-from-expenses (D-D8).
     */
    @Builder.Default
    private TimeEntry.BillingStatus billingStatus = TimeEntry.BillingStatus.UNBILLED;

    /**
     * Nullable idempotency anchor. Non-null iff {@code billingStatus == INVOICED}.
     * Symmetric with {@code Milestone.spawnedInvoiceId} (Phase-C) and
     * {@code TimeEntry.invoicedInvoiceId}.
     */
    private UUID invoicedInvoiceId;

    /**
     * Nullable denormalized fast-path to the registered receipt {@link
     * com.kumouri.kmodigipresbe.model.files.Attachment}. The canonical receipt link
     * is still the {@code ("EXPENSE", expenseId)} subject pair queryable via
     * {@code GET /attachments?subjectType=EXPENSE&subjectId=<id>} (D-D12).
     */
    private UUID receiptAttachmentId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** Expense approval workflow states (D-D10). */
    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
