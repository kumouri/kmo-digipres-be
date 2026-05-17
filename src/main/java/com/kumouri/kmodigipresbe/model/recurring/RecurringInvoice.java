package com.kumouri.kmodigipresbe.model.recurring;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
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
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A recurring-billing template (Phase E — E-D1, E-D2). One {@code @Document} per
 * recurrence schedule; the Quartz {@code RecurringInvoiceSpawnJob} materializes
 * exactly one DRAFT {@link Invoice} per cadence period via the existing
 * {@code InvoiceService.create} seam (the same DRAFT seam Phase C's milestone-spawn
 * and Phase D's invoice-from-time/expenses use — invoicing is NOT reinvented here).
 *
 * <p>Tenant-scoped + audited (a CRM-owned template). NOT {@code CustomFieldHost} —
 * it is a billing-automation template, not a CRM record (consistent with
 * Milestone/Task/TimeEntry/Expense).
 *
 * <h2>Idempotency model (E-D3 — the money core)</h2>
 * The spawn is made exactly-once per cadence period by the
 * {@link RecurringInvoiceOccurrence} unique-indexed ledger (ledger-insert FIRST,
 * before any Invoice is created) + an explicit-boolean occurrence probe — NEVER
 * {@code switchIfEmpty(spawn)}. {@link #nextRunAt} is the durable cursor; with the
 * E-D5 RAM Quartz store this cursor + the ledger ARE the restart-durability
 * mechanism (the JobStore is intentionally not the source of truth).
 */
@Document("recurring_invoices")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_status_idx", def = "{'tenantId':1,'status':1}"),
        @CompoundIndex(name = "tenant_nextrun_idx", def = "{'tenantId':1,'status':1,'nextRunAt':1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RecurringInvoice implements TenantScoped, Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /** Required — service validates (errorCode 3601 if blank). */
    private String templateName;

    /** Nullable header refs copied onto each spawned {@link Invoice}. */
    private UUID contactId;
    private UUID companyId;
    private UUID dealId;
    private UUID projectId;

    @Builder.Default
    private String currency = "USD";

    /**
     * Required non-empty (errorCode 3602). Template lines are deep-copied per spawn;
     * totals are computed by {@code InvoiceService.create} → {@code Quote.computeTotals()}.
     */
    @Builder.Default
    private List<LineItem> lineItems = List.of();

    /**
     * Required (errorCode 3603) — RFC-5545 RRULE body. Validated via the existing
     * {@code Rfc5545RecurringSchedule} (a malformed rule maps to the existing 1300,
     * NOT a new code — E-D11).
     */
    private String rrule;

    /** Required (errorCode 3604) — DTSTART anchor for RRULE expansion. */
    private Instant seedAt;

    /**
     * Copied to each spawned invoice (E-D8). Defaults to {@code NET_30}.
     */
    @Builder.Default
    private Invoice.PaymentTerms paymentTerms = Invoice.PaymentTerms.NET_30;

    /**
     * {@code true} ⇒ a spawned invoice is transitioned DRAFT→SENT (firing
     * {@code INVOICE_FINALIZED} → the shipped QuickBooksInvoiceSync); {@code false}
     * ⇒ it stays DRAFT (the safe Phase-C/D D10 posture — the default).
     */
    @Builder.Default
    private boolean autoFinalize = false;

    /** Only {@code ACTIVE} recurring invoices are spawned. */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** Nullable; the next occurrence the job will spawn — the durable cursor. */
    private Instant nextRunAt;

    /** Nullable; the most recent spawned occurrence instant (monotonic). */
    private Instant lastRunAt;

    /** Nullable; audit/trace pointer (symmetric with {@code Milestone.spawnedInvoiceId}). */
    private UUID lastSpawnedInvoiceId;

    @Builder.Default
    private int occurrenceCount = 0;

    /** Nullable hard stop — once reached the template auto-transitions to {@code ENDED}. */
    private Instant endAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** Recurring-invoice lifecycle. Only {@code ACTIVE} is spawned. */
    public enum Status { ACTIVE, PAUSED, ENDED }
}
