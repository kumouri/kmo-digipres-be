package com.kumouri.kmodigipresbe.model.billing;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schema-only for Phase 7 — Stripe integration in Phase 8 hooks
 * {@link #status} transitions and writes {@link Payment} entries. {@link #balance}
 * is recomputed from total − sum(payments) at read time (see the service).
 */
@Document("invoices")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1, 'issuedAt': -1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
// NOTE (Phase E — the user-authorized resolution of the escalated tenant_number_idx
// blocker): tenant_number_idx is deliberately NOT declared here. A Spring-Data
// @CompoundIndex cannot express a partialFilterExpression, and the OLD non-sparse
// non-partial unique index it used to declare was the blocker — it rejected a 2nd
// invoiceNumber==null invoice per tenant (E11000), breaking recurring catch-up
// (Phase E) and the latent Phase-C milestone-spawn / Phase-D time/expense-spawn
// multi-unnumbered-invoice case. The index is now owned end-to-end by
// InvoiceNumberIndexInitializer as a PARTIAL unique index (unique only when
// invoiceNumber exists & is non-null) so many null-numbered DRAFTs per tenant are
// allowed and uniqueness still holds for issued/numbered invoices. Exactly one
// tenant_number_idx definition exists, and it lives there.
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Invoice implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String invoiceNumber;

    @Builder.Default
    private Status status = Status.DRAFT;

    private UUID quoteId;
    private UUID dealId;
    private UUID contactId;
    private UUID companyId;

    /**
     * Phase C — nullable; set when an invoice is spawned from a Milestone completion
     * (C-D8). Allows tracing the invoice back to the originating project and milestone.
     */
    private UUID projectId;

    /**
     * Phase C — nullable; set when an invoice is spawned from a Milestone completion
     * (C-D8). Non-null iff {@link #projectId} is also non-null.
     */
    private UUID milestoneId;

    @Builder.Default
    private String currency = "USD";

    @Builder.Default
    private List<LineItem> lineItems = List.of();

    private BigDecimal subtotal;
    private BigDecimal discountTotal;
    private BigDecimal taxTotal;
    private BigDecimal total;

    /** Cached total − sum(payments). Recomputed by InvoiceService on read. */
    private BigDecimal balance;

    private LocalDate issuedAt;
    private LocalDate dueAt;

    /**
     * Phase E (E-D8) — payment terms driving {@link #dueAt} derivation. Additive and
     * <strong>nullable</strong>: {@code null} means "unspecified" and is treated as
     * {@link PaymentTerms#NET_30} only where {@code dueAt} is derived (an explicitly
     * supplied {@code dueAt} always wins; legacy invoices deserialize this as null
     * with zero migration — the {@code projectId}-nullable-additive Phase-C precedent).
     */
    private PaymentTerms paymentTerms;

    private Instant statusChangedAt;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    /**
     * External system identifiers — keyed by provider ({@code "quickbooks"},
     * {@code "stripe"}, ...) and carrying the external system's invoice id.
     * Used as the idempotency anchor for downstream sync (Phase 10d QBO).
     */
    @Builder.Default
    private Map<String, String> externalRefs = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { DRAFT, SENT, PARTIALLY_PAID, PAID, VOIDED, OVERDUE }

    /**
     * Phase E (E-D8) — invoice payment terms. The numeric variants encode a
     * net-days offset from the issue date; {@code DUE_ON_RECEIPT} is offset 0.
     */
    public enum PaymentTerms {
        DUE_ON_RECEIPT(0),
        NET_7(7),
        NET_15(15),
        NET_30(30),
        NET_45(45),
        NET_60(60);

        private final int netDays;

        PaymentTerms(int netDays) {
            this.netDays = netDays;
        }

        public int netDays() {
            return netDays;
        }
    }

    /**
     * Phase E (E-D8) — pure derivation of a due date from an issue date and payment
     * terms. Null-tolerant on both inputs: a null {@code issued} yields null; a null
     * {@code terms} defaults to {@link PaymentTerms#NET_30} (the "unspecified" rule).
     * Side-effect-free and total — safe to call from anywhere.
     */
    public static LocalDate deriveDueAt(LocalDate issued, PaymentTerms terms) {
        if (issued == null) {
            return null;
        }
        PaymentTerms effective = terms != null ? terms : PaymentTerms.NET_30;
        return issued.plusDays(effective.netDays());
    }
}
