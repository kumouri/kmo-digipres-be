package com.kumouri.kmodigipresbe.model.project;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate root for the project-delivery vertical (Phase C).
 *
 * <p>A Project is optionally created from a WON Deal via
 * {@code POST /projects/from-deal/{dealId}} (C-D6). Milestones and Tasks are
 * separate collections keyed by {@code projectId} (C-D1).
 *
 * <p>Project codes are generated atomically per-(tenant, year) in
 * {@link com.kumouri.kmodigipresbe.service.project.ProjectCodeGenerator}
 * with format {@code PRJ-{year}-{seq:03}} (C-D3).
 */
@Document("projects")
@CompoundIndex(name = "tenant_status_idx", def = "{'tenantId':1,'status':1}")
@CompoundIndex(name = "tenant_code_idx",   def = "{'tenantId':1,'code':1}", unique = true)
@CompoundIndex(name = "tenant_deal_idx",   def = "{'tenantId':1,'dealId':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Project implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    /** {@code PRJ-{year}-{seq:03}} — generated on create, immutable, unique per tenant. */
    private String code;

    /** Required — service validates (errorCode 3401 if blank). */
    private String name;

    @Builder.Default
    private ProjectStatus status = ProjectStatus.PLANNING;

    /** Nullable — set when spawned from a WON deal. */
    private UUID dealId;

    /** Nullable — copied from Deal on conversion. */
    private UUID primaryContactId;

    /** Nullable — copied from Deal on conversion. */
    private UUID companyId;

    /** Nullable — copied from Deal {@code ownerId} on conversion. */
    private UUID ownerId;

    private String description;

    private LocalDate startDate;

    private LocalDate targetEndDate;

    /** Set when status transitions to COMPLETED. */
    private LocalDate actualEndDate;

    /**
     * Additive nullable (Phase 3 — NMM coverage-window automation). For an NMM coverage
     * customer, the instant their active coverage window closes. Used <strong>only</strong>
     * as the selector for the default-OFF coverage-window check-in nudge job
     * ({@code CoverageNudgeJob}): a Project is "active coverage" iff
     * {@code coverageWindowEndsAt != null && coverageWindowEndsAt.isAfter(now)}. Null on every
     * legacy/non-coverage Project (deserializes null — no migration, no index change); the
     * Project core is otherwise untouched.
     */
    private Instant coverageWindowEndsAt;

    /**
     * Per-project opt-in for auto-finalizing milestone-spawned invoices (C-D10).
     * When {@code true}, a DRAFT invoice created on milestone completion is
     * immediately transitioned DRAFT→SENT (which emits {@code INVOICE_FINALIZED}).
     * Default {@code false} — invoices stay DRAFT.
     */
    @Builder.Default
    private boolean autoFinalizeMilestoneInvoices = false;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum ProjectStatus {
        PLANNING, ACTIVE, ON_HOLD, COMPLETED, CANCELLED
    }
}
