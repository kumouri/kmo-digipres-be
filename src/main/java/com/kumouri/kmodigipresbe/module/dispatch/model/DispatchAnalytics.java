package com.kumouri.kmodigipresbe.module.dispatch.model;

import java.time.LocalDate;

/**
 * T14 (Home "DispatchIQ") — the dispatch analytics for a day: how well the day's open work orders are
 * staffed (assigned vs unassigned), the skill-match quality, and the average fit. The result of
 * {@code GET /dispatch/analytics}. Computed from the same optimizer pass that builds the
 * {@link DispatchPlan}, so the dashboard and the proposed schedule are consistent.
 *
 * @param date           the UTC day
 * @param totalOpen      total open work orders for the day
 * @param assigned       how many the optimizer could staff
 * @param unassigned     how many it could not staff (no eligible/available tech)
 * @param skillMatched   how many assigned work orders went to a tech whose skills matched
 * @param skillMatchRate {@code skillMatched / assigned} in [0,1] (0 when none assigned)
 * @param avgFitScore    mean composite fit across the assigned work orders in [0,1] (0 when none)
 */
public record DispatchAnalytics(
        LocalDate date,
        int totalOpen,
        int assigned,
        int unassigned,
        int skillMatched,
        double skillMatchRate,
        double avgFitScore) {
}
