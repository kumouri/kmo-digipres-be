package com.kumouri.kmodigipresbe.model.contractor;

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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A per-(user, week) timesheet period that owns the submit→approve lifecycle
 * (Phase J — contractor / time-management vertical).
 *
 * <p>Time entries associate to a period via {@code TimeEntry.timesheetId}, set at log
 * time by find-or-create-open-period in {@code TimeEntryService}. The association is an
 * explicit FK (not a date-range derivation) so it is deterministic and midnight-split
 * aware — each split segment resolves its own period from its own {@code startedAt}, so a
 * session straddling a week boundary correctly lands its halves in two periods.
 *
 * <p>Lifecycle {@code OPEN → SUBMITTED → APPROVED}; {@code SUBMITTED → REJECTED};
 * {@code REJECTED → OPEN} (contractor reopen to fix and resubmit). Approval flips the
 * member entries' {@code TimeEntry.approved} flag (the invoice/payout gate) in the same
 * operation — single writer, so the money path stays an O(1) filter with no drift.
 *
 * <p>The unique {@code (tenantId, userId, periodStart)} index is the find-or-create-open
 * idempotency backstop (a concurrent create → {@code DuplicateKeyException} → re-read).
 */
@Document("timesheets")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_user_period_idx", def = "{'tenantId':1,'userId':1,'periodStart':1}", unique = true),
        @CompoundIndex(name = "tenant_status_idx",      def = "{'tenantId':1,'status':1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Timesheet implements Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /** Whose period this is. */
    private UUID userId;

    /** Inclusive period start — Monday of the ISO week, in the logging zone. */
    private LocalDate periodStart;

    /** Inclusive period end — Sunday of the ISO week. */
    private LocalDate periodEnd;

    @Builder.Default
    private Status status = Status.OPEN;

    /** Set on submit. */
    private Instant submittedAt;

    /** Set on approve/reject — who decided (ADMIN). */
    private UUID approvedBy;

    /** Set on approve/reject — when. */
    private Instant approvedAt;

    /** Submitter note (on submit) or rejection reason (on reject — errorCode 4151 if blank). */
    private String note;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { OPEN, SUBMITTED, APPROVED, REJECTED }
}
