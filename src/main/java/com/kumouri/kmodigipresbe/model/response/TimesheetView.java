package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.contractor.Timesheet;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Contractor-facing timesheet projection (Phase J — J3), the contractor analogue of
 * {@link ContractorProjectView} and the J2 projection posture.
 *
 * <p>Deliberately omits {@code tenantId} and {@code version} — tenant scoping is internal
 * and must not leak to a scoped contractor. {@code userId} stays (it is always the caller's
 * own self on the {@code /me/contractor/timesheets} surface) so the FE can correlate. The
 * {@code note} carries the submitter note or the admin rejection reason; {@code approvedBy}
 * is the deciding ADMIN's id.
 */
public record TimesheetView(
        String id,
        String userId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Timesheet.Status status,
        Instant submittedAt,
        String approvedBy,
        Instant approvedAt,
        String note) {

    public static TimesheetView from(Timesheet ts) {
        return new TimesheetView(
                ts.getId() == null ? null : ts.getId().toString(),
                ts.getUserId() == null ? null : ts.getUserId().toString(),
                ts.getPeriodStart(),
                ts.getPeriodEnd(),
                ts.getStatus(),
                ts.getSubmittedAt(),
                ts.getApprovedBy() == null ? null : ts.getApprovedBy().toString(),
                ts.getApprovedAt(),
                ts.getNote());
    }
}
