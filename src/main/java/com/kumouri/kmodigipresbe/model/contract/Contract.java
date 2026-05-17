package com.kumouri.kmodigipresbe.model.contract;

import com.kumouri.kmodigipresbe.audit.Auditable;
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
import java.util.Map;
import java.util.UUID;

/**
 * An executable contract document (Phase F — F-D3). Lifecycle:
 * {@code DRAFT → SENT → SIGNED} (terminal-happy) and
 * {@code DRAFT|SENT → VOIDED} (terminal). A {@code SIGNED} contract is immutable —
 * an amendment is a NEW {@code Contract} with {@link #parentContractId} pointing at
 * the contract it amends; no in-place mutation of a signed contract is ever
 * permitted (legal-integrity invariant, §7).
 *
 * <p>Tenant-scoped + audited ({@link Auditable}).
 * NOT {@code CustomFieldHost} (consistent with Phase-C/D/E entities).
 *
 * <h2>Indexes</h2>
 * <ul>
 *   <li>{@code tenant_status_idx} — list-by-status queries</li>
 *   <li>{@code tenant_deal_idx} — "contracts for a deal" lookups</li>
 *   <li><strong>{@code tenant_number_idx}</strong> — partial-unique
 *       ({@code partialFilterExpression: {'contractNumber':{'$type':'string'}}}).
 *       DRAFTs stay {@code contractNumber=null}; uniqueness holds only for issued
 *       (SENT+) contracts. Spring Data's {@code @CompoundIndex} cannot express a
 *       {@code partialFilterExpression} (the E.11 lesson), so the annotation
 *       carries only the key fields; the partial-unique definition is owned end-to-end
 *       by {@code scheduling.ContractNumberIndexInitializer} (the
 *       {@code InvoiceNumberIndexInitializer} pattern).</li>
 * </ul>
 */
@Document("contracts")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_status_idx",
                def = "{'tenantId':1,'status':1}"),
        @CompoundIndex(name = "tenant_deal_idx",
                def = "{'tenantId':1,'dealId':1}"),
        @CompoundIndex(name = "tenant_number_idx",
                def = "{'tenantId':1,'contractNumber':1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Contract implements TenantScoped, Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /**
     * Nullable until SENT. Format {@code CTR-{YYYY}-{NNNN}} from
     * {@code ContractNumberGenerator}. The partial-unique {@code tenant_number_idx}
     * enforces uniqueness only for non-null values so many null-numbered DRAFTs
     * per tenant are legal.
     */
    private String contractNumber;

    /**
     * Required (errorCode 3703 if blank). The rendered title (jmustache-expanded
     * at create from the template's {@link ContractTemplate#getDefaultTitle()}, or
     * caller-supplied).
     */
    private String title;

    /**
     * Contract kind — copied from the source {@link ContractTemplate}. {@code SOW}
     * drives the Deal→WON+Project promotion on signing (F-D9).
     */
    @Builder.Default
    private ContractTemplate.Kind kind = ContractTemplate.Kind.GENERIC;

    /** Current lifecycle status. Defaults to {@code DRAFT}. */
    @Builder.Default
    private Status status = Status.DRAFT;

    /** The source {@link ContractTemplate} (audit/trace). Nullable. */
    private UUID templateId;

    /**
     * Amendment model (ultraplan note): an amendment is a NEW {@code Contract}
     * whose {@code parentContractId} points at the contract it amends. No in-place
     * mutation of a signed contract is ever performed. Nullable.
     */
    private UUID parentContractId;

    /** Header ref to the associated Deal. Nullable. Set when spawned from a Quote. */
    private UUID dealId;

    /** Header ref to the associated Contact. Nullable. */
    private UUID contactId;

    /** Header ref to the associated Company. Nullable. */
    private UUID companyId;

    /** Header ref to the source Quote. Nullable. Set when spawned from a Quote. */
    private UUID quoteId;

    /**
     * The jmustache binding context snapshotted at create time. Snapshotting ensures
     * that later edits to the template never change an executed contract.
     */
    @Builder.Default
    private Map<String, Object> variables = Map.of();

    /**
     * S3 storage ref of the <em>pre-signature</em> rendered PDF sent to Documenso.
     * Key pattern: {@code tenants/<tenantId>/contracts/<id>/rendered.pdf}.
     * Nullable until SENT.
     */
    private String renderedPdfStorageRef;

    /**
     * The Documenso-side document id returned by the send call. The webhook
     * correlation key (F-D7). Nullable until SENT.
     */
    private String documensoDocumentId;

    /** Timestamp when the Documenso send succeeded (DRAFT→SENT). Nullable. */
    private Instant sentAt;

    /**
     * Timestamp populated by the {@code document.signed} webhook (AC-F1). Nullable.
     * Setting this field is the only path that moves status to SIGNED — the verified
     * webhook is the gate; the API cannot move SENT→SIGNED directly.
     */
    private Instant signedAt;

    /**
     * S3 storage ref of the Documenso-served <em>signed</em> PDF (AC-F1).
     * Key pattern: {@code tenants/<tenantId>/contracts/<id>/signed.pdf}.
     * Nullable until signed. <strong>Legal integrity:</strong> stores exactly the
     * bytes Documenso served — never re-rendered after signing.
     */
    private String signedPdfStorageRef;

    /**
     * Explicit-boolean idempotency anchor for the Deal→WON+Project promotion (AC-F2).
     * Set to {@code true} once {@code DealCrudService.moveStage(WON)} +
     * {@code ProjectService.convertFromDeal} have been applied for this SOW.
     * Doubly idempotent: this flag + {@code convertFromDeal}'s own
     * {@code existsByTenantIdAndDealId} guard.
     */
    @Builder.Default
    private boolean promotedDealToWon = false;

    /**
     * The Project spawned by the signed-SOW promotion (audit/trace). Nullable.
     * Symmetric with {@code Milestone.spawnedInvoiceId}.
     */
    private UUID spawnedProjectId;

    /** Required when status→VOIDED (errorCode 3706). Nullable otherwise. */
    private String voidReason;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Contract lifecycle. {@code SIGNED} and {@code VOIDED} are terminal.
     * No transition out of {@code SIGNED} via the API — only the verified Documenso
     * webhook moves {@code SENT→SIGNED}. Amendments are new {@code Contract}s with
     * {@code parentContractId}.
     */
    public enum Status {
        DRAFT, SENT, SIGNED, VOIDED
    }
}
