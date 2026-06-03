package com.kumouri.kmodigipresbe.integration.moletripwire;

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
 * The coverage-window check-in nudge idempotency ledger (Phase 3 — NMM coverage-window automation,
 * piece B). One row per (tenant, project, period). The unique {@code tenant_project_period_idx}
 * compound index is the exactly-once guarantee per nudge period: the default-OFF
 * {@code CoverageNudgeJob} inserts this row <strong>FIRST</strong> (before sending the check-in SMS),
 * so a restart / re-run / concurrent fire within the same period loses on a
 * {@code DuplicateKeyException} and sends ZERO duplicate nudge.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same rationale as
 * {@code RecurringInvoiceOccurrence} / {@code StripeWebhookEvent}: an infrastructure dedupe record,
 * not a CRM entity — it produces no audit events).
 */
@Document("coverage_nudge_logs")
@CompoundIndex(
        name = "tenant_project_period_idx",
        def = "{'tenantId':1,'projectId':1,'periodKey':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CoverageNudgeLog implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The coverage customer's {@link com.kumouri.kmodigipresbe.model.project.Project} id; part of the unique key. */
    private UUID projectId;

    /**
     * The nudge period identity (a String, e.g. ISO week {@code "2026-W23"}) and part of the unique
     * key — one nudge per (project, period). A String, not a date, so the index key is a stable
     * canonical bucket independent of the exact send instant.
     */
    private String periodKey;

    /** The Contact the nudge SMS was sent to (nullable — null if the Project had no reachable phone). */
    private UUID contactId;

    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
