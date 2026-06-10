package com.kumouri.kmodigipresbe.module.dispatch.service;

import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchAnalytics;
import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchPlan;
import com.kumouri.kmodigipresbe.module.dispatch.model.ProposedAssignment;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * T14 (Home "DispatchIQ") — dispatch analytics ({@code GET /dispatch/analytics}): how well a day's open
 * work orders are staffed (assigned vs unassigned), the skill-match quality, and the average fit. Derived
 * from the <strong>same optimizer pass</strong> that builds the {@link DispatchPlan} (via
 * {@link DispatchPlanService#optimize}), so the dashboard and the proposed schedule are always consistent.
 */
@RequiredArgsConstructor
public class DispatchAnalyticsService {

    private final DispatchPlanService planService;

    public Mono<DispatchAnalytics> analytics(LocalDate date) {
        return planService.optimize(date).map(plan -> project(date, plan));
    }

    private static DispatchAnalytics project(LocalDate date, DispatchPlan plan) {
        int skillMatched = (int) plan.assignments().stream()
                .filter(ProposedAssignment::skillMatched).count();
        return new DispatchAnalytics(
                date,
                plan.openCount(),
                plan.assignedCount(),
                plan.unassignedCount(),
                skillMatched,
                plan.skillMatchRate(),
                plan.avgFitScore());
    }
}
