package com.kumouri.kmodigipresbe.module.dispatch.model;

import java.time.LocalDate;
import java.util.List;

/**
 * T14 (Home "DispatchIQ") — the proposed dispatch schedule for a day: the optimizer's
 * {@link ProposedAssignment}s split into the work orders it could staff ({@link #assignments}) and the
 * ones it could not ({@link #unassigned}, each carrying an {@code unassignedReason}), with summary counts
 * a dispatcher reviews before applying. The result of {@code GET /dispatch/optimize}.
 *
 * <p>{@link #assignments} is ordered <strong>priority-first</strong> (the greedy assignment order —
 * EMERGENCY/high-value/earliest first) so the dispatcher reads the urgent jobs at the top.
 * {@link #unassigned} surfaces jobs no available skilled tech could take — the optimizer never
 * mis-assigns a wrong-skill tech to fill a gap.
 *
 * @param date           the UTC day this plan covers
 * @param assignments    the proposed (work order → tech) assignments, priority-first
 * @param unassigned     the open work orders the optimizer could not staff (with reasons)
 * @param openCount      total open work orders considered
 * @param assignedCount  how many were assigned ({@code assignments.size()})
 * @param unassignedCount how many could not be staffed ({@code unassigned.size()})
 * @param skillMatchRate fraction of assigned work orders whose tech's skills matched the service type
 * @param avgFitScore    mean composite fit score across the assigned work orders (0 when none)
 */
public record DispatchPlan(
        LocalDate date,
        List<ProposedAssignment> assignments,
        List<ProposedAssignment> unassigned,
        int openCount,
        int assignedCount,
        int unassignedCount,
        double skillMatchRate,
        double avgFitScore) {
}
