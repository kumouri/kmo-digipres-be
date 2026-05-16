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
import java.util.UUID;

/**
 * Records a unit of billable work time (Phase D — D-D1, D-D2).
 *
 * <p>This is the highest-write entity in the system (ultraplan row D). The leading
 * compound index {@code (tenantId, userId, startedAt desc)} is mandatory for
 * user-scoped timesheet queries and the weekly aggregation window.
 *
 * <h2>Running vs. stopped timer (D-D3)</h2>
 * <ul>
 *   <li><strong>Running timer:</strong> {@code endedAt == null}, {@code durationSeconds == 0}.
 *       At most one per {@code (tenantId, userId)} — enforced by an explicit boolean guard
 *       in {@link com.kumouri.kmodigipresbe.service.timetracking.TimeEntryService}, not a
 *       unique index (sparse-null pitfall; the Phase-C C-D4 rationale).</li>
 *   <li><strong>Stopped entry:</strong> {@code endedAt != null}, {@code durationSeconds}
 *       persisted (authoritative for billing — deterministic, immune to read-time drift).</li>
 * </ul>
 *
 * <h2>Midnight split (D-D3)</h2>
 * When a timer session spans a local-day boundary (zone precedence: request {@code zoneId}
 * → {@code kmosf.timetracking.default-zone} → {@code ZoneOffset.UTC}), the session is
 * materialized as N rows at stop time — one per local calendar day. All segments share a
 * non-null {@code splitGroupId}. Same-local-day sessions produce exactly one row with
 * {@code splitGroupId == null} (the common-case no-op).
 *
 * <h2>Manual entry (D-D4 / Decision D9)</h2>
 * Manual entry is the first-class primary path. {@code source=MANUAL} is the default.
 * {@code source=TIMER} records provenance only — the entry is the same entity regardless
 * of how it was produced.
 */
@Document("time_entries")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_user_started_idx", def = "{'tenantId':1,'userId':1,'startedAt':-1}"),
        @CompoundIndex(name = "tenant_project_idx",      def = "{'tenantId':1,'projectId':1}"),
        @CompoundIndex(name = "tenant_task_idx",         def = "{'tenantId':1,'taskId':1}"),
        @CompoundIndex(name = "tenant_billing_idx",      def = "{'tenantId':1,'billingStatus':1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TimeEntry implements Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /**
     * Who logged the time. Required — service validates (errorCode 3501 if null).
     * Set from {@code ctx.userId()} on create unless explicitly supplied (manual
     * entry on behalf of another user).
     */
    private UUID userId;

    /** Nullable FK to Phase-C {@code Project}. */
    private UUID projectId;

    /** Nullable FK to Phase-C {@code Task} (preferred granularity). */
    private UUID taskId;

    /** Nullable free-text work note. */
    private String description;

    /**
     * Required (errorCode 3502 if null). UTC instant — the leading-index sort key.
     * The live elapsed for a running timer is computed client-side from {@code startedAt};
     * it is NEVER persisted as a moving value.
     */
    private Instant startedAt;

    /**
     * Nullable — {@code null} iff this is a still-running timer. Non-null iff stopped.
     * Service validates {@code endedAt > startedAt} (errorCode 3503).
     */
    private Instant endedAt;

    /**
     * Derived, persisted, authoritative for billing. For a stopped entry:
     * {@code floor(endedAt - startedAt)} in whole seconds. For a running timer: {@code 0}.
     * Deterministic — not recomputed at read time.
     */
    @Builder.Default
    private long durationSeconds = 0L;

    /**
     * Provenance of this entry (D-D4 / Decision D9). {@code MANUAL} is first-class;
     * {@code TIMER} records that the entry was produced by the timer API but is
     * otherwise identical.
     */
    @Builder.Default
    private TimeEntrySource source = TimeEntrySource.MANUAL;

    @Builder.Default
    private boolean billable = true;

    /**
     * Invoice billing state (D-D6 idempotency anchor). Non-null.
     */
    @Builder.Default
    private BillingStatus billingStatus = BillingStatus.UNBILLED;

    /**
     * Nullable; set when this entry is rolled into an invoice. Non-null iff
     * {@code billingStatus == INVOICED}. Symmetric with
     * {@code Milestone.spawnedInvoiceId} (Phase-C idempotency pattern, batched).
     */
    private UUID invoicedInvoiceId;

    /**
     * Nullable; hourly rate captured at log time for the invoice line. Null ⇒ the
     * invoice-from-time caller must supply a {@code defaultRateAmount} (3521 if neither
     * is set on a billable entry).
     */
    private BigDecimal rateAmount;

    /**
     * Nullable; non-null when this row is one of a midnight-split pair/chain. Links
     * all segments of the originating timer session for UI grouping and split-aware
     * invoice aggregation (D-D6a).
     */
    private UUID splitGroupId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** Provenance of a time entry (Decision D9 — manual is first-class). */
    public enum TimeEntrySource {
        TIMER,
        MANUAL
    }

    /** Invoice billing lifecycle (idempotency dimension for invoice-from-time, D-D6). */
    public enum BillingStatus {
        UNBILLED,
        INVOICED
    }
}
