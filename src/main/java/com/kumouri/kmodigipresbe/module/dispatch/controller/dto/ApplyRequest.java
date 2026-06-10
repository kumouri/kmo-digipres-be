package com.kumouri.kmodigipresbe.module.dispatch.controller.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * T14 (Home "DispatchIQ") — the {@code POST /dispatch/apply} request body: the dispatcher's reviewed (and
 * possibly edited) assignment decisions for a day. The dispatcher reviews the {@code GET /dispatch/optimize}
 * proposal, edits as needed, and posts back the decisions to commit — apply never blindly re-runs the
 * optimizer, it commits exactly what the dispatcher approved.
 *
 * @param date        the UTC day the assignments are for (echoed onto the {@code DISPATCH_PLAN_APPLIED} event)
 * @param assignments the (work order → technician) decisions to commit
 */
public record ApplyRequest(
        LocalDate date,
        List<Decision> assignments) {

    /**
     * One assignment decision.
     *
     * @param workOrderId the open work order to (re)assign
     * @param techUserId  the technician to assign it to (null clears the assignment)
     */
    public record Decision(UUID workOrderId, UUID techUserId) {
    }
}
