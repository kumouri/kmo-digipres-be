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
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * A reusable contract template (Phase F — F-D2). The {@code bodyTemplate} field is
 * a jmustache source rendered by {@code ContractPdfService} against the
 * {@link Contract#getVariables()} snapshot at create time.
 *
 * <p>Tenant-scoped + audited ({@link Auditable}) — it is a CRM-owned template, not
 * a system ledger. NOT {@code CustomFieldHost} (consistent with
 * Milestone/Task/TimeEntry/Expense/RecurringInvoice).
 *
 * <h2>Index</h2>
 * Non-unique compound {@code tenant_name_idx} ({@code tenantId + name}) for listing
 * by tenant; names need not be globally unique.
 */
@Document("contract_templates")
@CompoundIndex(name = "tenant_name_idx", def = "{'tenantId':1,'name':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplate implements TenantScoped, Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /**
     * Required (errorCode 3701 if blank). Display name for the template — used in
     * the wizard and in the spawned contract's history.
     */
    private String name;

    /** Optional human description. Nullable. */
    private String description;

    /**
     * Contract kind (closed set). Determines downstream behavior:
     * {@code SOW} templates spawn the Deal→WON+Project promotion on signing.
     * Defaults to {@code GENERIC}.
     */
    @Builder.Default
    private Kind kind = Kind.GENERIC;

    /**
     * Required (errorCode 3702 if blank). The jmustache template source rendered
     * by {@code ContractPdfService} against {@link Contract#getVariables()}.
     */
    private String bodyTemplate;

    /**
     * Optional jmustache source for the contract title. If {@code null}, the spawned
     * contract title falls back to {@code name}.
     */
    private String defaultTitle;

    /**
     * Whether this template is selectable by the wizard / Quote-ACCEPTED flow.
     * Inactive templates are never returned by the "find active for kind" query.
     * Defaults to {@code true}.
     */
    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Closed-set contract kind. {@code SOW} drives the Deal→WON+Project promotion
     * on signing (F-D9). Stored as a String constant so changes to a template's kind
     * are audited and filter queries remain index-friendly.
     */
    public enum Kind {
        SOW, MSA, NDA, GENERIC
    }
}
