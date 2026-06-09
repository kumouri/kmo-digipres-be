package com.kumouri.kmodigipresbe.module.ar;

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
 * The "Get Paid" AR / collections dunning idempotency ledger (band 4600-4619). One row per
 * (tenant, invoice, tier) — a tier is fired at most once per overdue invoice. The unique
 * {@code tenant_invoice_tier_idx} compound index is the exactly-once guarantee: the default-OFF
 * {@link ArAgingSweepJob} inserts this row <strong>FIRST</strong> (before flipping the invoice
 * SENT→OVERDUE and before emitting the {@code INVOICE_OVERDUE_*} event), so a restart / re-run /
 * concurrent fire loses on a {@code DuplicateKeyException} and emits ZERO duplicate dunning event —
 * the {@code CoverageNudgeLog} / {@code RecurringInvoiceOccurrence} ledger-insert-FIRST pattern
 * verbatim.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same rationale as
 * {@code CoverageNudgeLog} / {@code RecurringInvoiceOccurrence} / {@code StripeWebhookEvent}: an
 * infrastructure dedupe record, not a CRM entity — it produces no audit events).
 */
@Document("dunning_logs")
@CompoundIndex(
        name = "tenant_invoice_tier_idx",
        def = "{'tenantId':1,'invoiceId':1,'tier':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DunningLog implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The overdue {@link com.kumouri.kmodigipresbe.model.billing.Invoice} id; part of the unique key. */
    private UUID invoiceId;

    /**
     * The overdue tier this row records — {@code D3} / {@code D7} / {@code D14}, crossed at 3 / 7 / 14
     * days past {@code dueAt} (+ grace). Part of the unique key — one row per (invoice, tier).
     */
    private DunningTier tier;

    /** The Contact the overdue invoice is for (nullable — null if the invoice carries no contactId). */
    private UUID contactId;

    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** The escalating overdue tiers, crossed at 3 / 7 / 14 days past due. */
    public enum DunningTier { D3, D7, D14 }
}
