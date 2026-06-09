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
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Get Paid" AR-4 — a <strong>Promise-to-Pay</strong> record: a staff-logged commitment from a
 * customer that they will pay the given invoice by a specific date. One promise per
 * (tenant, invoice) at most is ACTIVE at any time, though the model allows multiple promises
 * per invoice over its lifecycle (the prior promises are KEPT / BROKEN / CANCELLED).
 *
 * <p>Used by {@link DunningDispatchService} to <strong>suppress dunning SMS</strong> when an
 * ACTIVE promise with a future {@code promisedDate} exists: the customer committed — don't nag
 * them until the date lapses. System / staff ledger entry — {@code TenantScoped} for tenant
 * isolation but <strong>NOT {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong>
 * (the {@code DunningLog} / {@code RecurringInvoiceOccurrence} system-ledger rationale).
 */
@Document("ar_promises_to_pay")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_invoice_idx",
                def = "{'tenantId':1,'invoiceId':1,'status':1}"),
        @CompoundIndex(name = "tenant_promise_created_idx",
                def = "{'tenantId':1,'createdAt':-1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PromiseToPay implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /** The invoice this promise relates to. */
    private UUID invoiceId;

    /** The contact who made the promise — nullable (promise recorded without a linked contact). */
    private UUID contactId;

    /**
     * The amount the customer promised to pay — nullable (a commitment to pay the full balance
     * without a specific partial amount). When null, interpreted as "pay the full outstanding
     * balance".
     */
    private BigDecimal promisedAmount;

    /** The date the customer promised to pay by. Must be today-or-future when created. */
    private LocalDate promisedDate;

    /**
     * Lifecycle status of this promise.
     * <ul>
     *   <li>{@code ACTIVE} — the promise is in effect and has not lapsed;</li>
     *   <li>{@code KEPT} — the invoice was paid on/before the {@code promisedDate};</li>
     *   <li>{@code BROKEN} — the {@code promisedDate} passed without full payment;</li>
     *   <li>{@code CANCELLED} — staff cancelled the promise (e.g. re-negotiated).</li>
     * </ul>
     */
    private Status status;

    /** Optional free-text note recorded by staff (e.g. "customer called back, promises EFT today"). */
    private String note;

    /** The instant this promise was created; the server-assigned creation timestamp. */
    @CreatedDate
    private Instant createdAt;

    /** The staff {@code User.id} who logged this promise — nullable when recorded programmatically. */
    private UUID createdByUserId;

    @Version
    private Long version;

    @LastModifiedDate
    private Instant updatedAt;

    /** Promise lifecycle. */
    public enum Status { ACTIVE, KEPT, BROKEN, CANCELLED }
}
