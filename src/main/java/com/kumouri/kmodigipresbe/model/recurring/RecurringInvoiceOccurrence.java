package com.kumouri.kmodigipresbe.model.recurring;

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
 * The recurring-invoice idempotency ledger (Phase E — E-D2, E-D3 — the money core).
 *
 * <p>One row per (tenant, recurringInvoiceId, period). The unique
 * {@code tenant_recurring_period_idx} compound index is the exactly-once guarantee
 * for recurring spawn: the spawn flow inserts this row <strong>FIRST</strong>
 * (before {@code InvoiceService.create}), so a Quartz re-fire / misfire / restart
 * race loses on a {@code DuplicateKeyException} and produces ZERO invoice, ZERO
 * double-bill, ZERO orphan DRAFT.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but
 * <strong>NOT {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same
 * rationale as {@code IdempotencyKey} / {@code StripeWebhookEvent}: an
 * infrastructure dedupe record, not a CRM entity — it produces no audit events).
 */
@Document("recurring_invoice_occurrences")
@CompoundIndex(
        name = "tenant_recurring_period_idx",
        def = "{'tenantId':1,'recurringInvoiceId':1,'periodKey':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RecurringInvoiceOccurrence implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The parent {@link RecurringInvoice} id; part of the unique key. */
    private UUID recurringInvoiceId;

    /**
     * ISO-8601 string of the occurrence {@code Instant} — the period identity and
     * part of the unique key. (A String, not an Instant, so the index key is a
     * stable canonical form independent of BSON date precision.)
     */
    private String periodKey;

    /**
     * Nullable until back-filled. The DRAFT invoice this occurrence materialized;
     * remains {@code null} on the duplicate-fire loser (which never creates one).
     */
    private UUID spawnedInvoiceId;

    private Instant spawnedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
