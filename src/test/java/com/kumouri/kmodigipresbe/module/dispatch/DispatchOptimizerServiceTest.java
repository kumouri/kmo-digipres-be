package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchPlan;
import com.kumouri.kmodigipresbe.module.dispatch.model.ProposedAssignment;
import com.kumouri.kmodigipresbe.module.dispatch.model.TechCandidate;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchOptimizerService;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T14 — {@link DispatchOptimizerService} pure-engine unit test (no Docker, fast). The marquee proofs:
 * <ul>
 *   <li><strong>Skill match</strong> — a tech whose declared {@code skills} cover the job's
 *       {@code serviceType} is preferred and the assignment is flagged {@code skillMatched}.</li>
 *   <li><strong>Priority-first</strong> — an EMERGENCY job is assigned the best free tech before a
 *       ROUTINE one (the urgent job claims the slot first).</li>
 *   <li><strong>Unstaffable job</strong> — a job whose only candidates have a declared mismatched skill
 *       is surfaced <em>unassigned with a reason</em>, never mis-assigned.</li>
 *   <li><strong>Load-balancing</strong> — two equal-skill free techs split the jobs.</li>
 *   <li><strong>Deterministic</strong> — the same inputs yield the same plan across runs.</li>
 *   <li><strong>Rationale + clamp</strong> — every assignment carries a non-blank rationale and the
 *       composite + components stay in [0,1].</li>
 * </ul>
 * Pure over the lists the orchestrator passes in — no Spring, no Mongo.
 */
class DispatchOptimizerServiceTest {

    private final DispatchOptimizerService optimizer = new DispatchOptimizerService();

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    // ── helpers ──────────────────────────────────────────────────────────────────

    private WorkOrder wo(String serviceType, String urgency, String valueBand, UUID jobSiteId, int hour) {
        Map<String, Object> cf = new HashMap<>();
        if (urgency != null) {
            cf.put("urgency", urgency);
        }
        if (valueBand != null) {
            cf.put("jobValueBand", valueBand);
        }
        return WorkOrder.builder()
                .id(UUID.randomUUID())
                .serviceType(serviceType)
                .status(WorkOrderStatus.SCHEDULED)
                .jobSiteId(jobSiteId)
                .scheduledStart(DAY.atTime(hour, 0).toInstant(ZoneOffset.UTC))
                .customFields(cf)
                .build();
    }

    private TechCandidate tech(String name, List<String> skills) {
        return new TechCandidate(UUID.randomUUID(), name, skills, 0, null);
    }

    // ── 1. skill-matched tech is preferred + flagged ────────────────────────────────

    @Test
    void skillMatchedTechIsPreferredAndFlagged() {
        TechCandidate hvac = tech("Dana HVAC", List.of("HVAC"));
        TechCandidate plumber = tech("Priya Plumbing", List.of("PLUMBING"));

        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(wo("HVAC", "ROUTINE", "MEDIUM", null, 9)),
                List.of(hvac, plumber),
                Map.of());

        assertThat(plan.assignments()).hasSize(1);
        ProposedAssignment a = plan.assignments().get(0);
        // The plumber's declared PLUMBING skill mismatches HVAC → ineligible; the HVAC tech wins.
        assertThat(a.assignedTechUserId()).isEqualTo(hvac.userId());
        assertThat(a.skillMatched()).isTrue();
        assertThat(a.skillFit()).isGreaterThan(0.5);
        assertThat(a.rationale()).contains("HVAC");
        assertThat(plan.skillMatchRate()).isEqualTo(1.0);
    }

    // ── 2. priority-first: EMERGENCY claims the best free tech before ROUTINE ─────────

    @Test
    void emergencyJobIsAssignedBeforeRoutine_andRankedFirst() {
        // One free HVAC tech, two HVAC jobs: a ROUTINE saved/listed first and an EMERGENCY second.
        // Priority-first ordering must process the EMERGENCY first (it should head the assignments list),
        // and because the single tech is free for both, both get assigned — but the EMERGENCY is #0.
        TechCandidate dana = tech("Dana", List.of("HVAC"));
        TechCandidate marco = tech("Marco", List.of("HVAC"));
        WorkOrder routine = wo("HVAC", "ROUTINE", "SMALL", null, 8);
        WorkOrder emergency = wo("HVAC", "EMERGENCY", "LARGE", null, 15);

        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(routine, emergency),
                List.of(dana, marco),
                Map.of());

        assertThat(plan.assignments()).hasSize(2);
        // The EMERGENCY job is assigned first (heads the priority-first list).
        ProposedAssignment first = plan.assignments().get(0);
        assertThat(first.workOrderId()).isEqualTo(emergency.getId());
        assertThat(first.urgency()).isEqualTo("EMERGENCY");
        assertThat(first.priority()).isGreaterThan(plan.assignments().get(1).priority());
        // Both equal-skill free techs are used → the two jobs go to different techs (load balance).
        assertThat(plan.assignments().get(0).assignedTechUserId())
                .isNotEqualTo(plan.assignments().get(1).assignedTechUserId());
    }

    // ── 3. unstaffable job → surfaced unassigned, never mis-assigned ─────────────────

    @Test
    void jobWithNoSkilledTech_isSurfacedUnassigned_notMisAssigned() {
        // Only a plumber is on the crew; an HVAC job cannot be staffed by a declared-PLUMBING-only tech.
        TechCandidate plumber = tech("Priya Plumbing", List.of("PLUMBING"));

        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(wo("HVAC", "URGENT", "MEDIUM", null, 10)),
                List.of(plumber),
                Map.of());

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unassigned()).hasSize(1);
        ProposedAssignment u = plan.unassigned().get(0);
        assertThat(u.assignedTechUserId()).isNull();
        assertThat(u.unassignedReason()).isNotBlank();
        assertThat(u.unassignedReason()).contains("HVAC");
        assertThat(plan.unassignedCount()).isEqualTo(1);
    }

    // ── 3b. a no-declared-skills tech CAN fill an otherwise-unstaffable job ───────────

    @Test
    void noDeclaredSkillsTech_isEligibleFallback() {
        TechCandidate plumber = tech("Priya Plumbing", List.of("PLUMBING"));
        TechCandidate generalist = tech("Sam Generalist", List.of()); // no declared skills → neutral, eligible

        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(wo("HVAC", "URGENT", "MEDIUM", null, 10)),
                List.of(plumber, generalist),
                Map.of());

        assertThat(plan.assignments()).hasSize(1);
        ProposedAssignment a = plan.assignments().get(0);
        assertThat(a.assignedTechUserId()).isEqualTo(generalist.userId());
        assertThat(a.skillMatched()).isFalse(); // assigned but not a declared-skill match
        assertThat(a.rationale()).contains("general");
    }

    // ── 4. load-balancing across two equal-skill free techs ──────────────────────────

    @Test
    void twoEqualSkillFreeTechs_splitTheJobs() {
        TechCandidate dana = tech("Dana", List.of("HVAC"));
        TechCandidate marco = tech("Marco", List.of("HVAC"));
        // Four identical HVAC routine jobs (no location → proximity neutral for all) → 2 each.
        List<WorkOrder> jobs = List.of(
                wo("HVAC", "ROUTINE", "MEDIUM", null, 8),
                wo("HVAC", "ROUTINE", "MEDIUM", null, 9),
                wo("HVAC", "ROUTINE", "MEDIUM", null, 10),
                wo("HVAC", "ROUTINE", "MEDIUM", null, 11));

        DispatchPlan plan = optimizer.optimize(DAY, jobs, List.of(dana, marco), Map.of());

        assertThat(plan.assignments()).hasSize(4);
        long danaCount = plan.assignments().stream()
                .filter(a -> dana.userId().equals(a.assignedTechUserId())).count();
        long marcoCount = plan.assignments().stream()
                .filter(a -> marco.userId().equals(a.assignedTechUserId())).count();
        assertThat(danaCount).isEqualTo(2);
        assertThat(marcoCount).isEqualTo(2);
    }

    // ── 4b. proximity prefers the closer tech's route ───────────────────────────────

    @Test
    void proximity_prefersTechAlreadyNearTheJob() {
        // Two HVAC techs; Dana already anchored near the job, Marco far away. The near tech should win
        // even though both are equally skilled and equally loaded.
        UUID jsId = UUID.randomUUID();
        JobSite js = JobSite.builder().id(jsId).location(LatLng.of(38.6270, -90.1994)).build(); // downtown STL
        Map<UUID, JobSite> sites = Map.of(jsId, js);

        TechCandidate near = new TechCandidate(UUID.randomUUID(), "Near Dana", List.of("HVAC"),
                1, LatLng.of(38.6300, -90.2000));  // ~0.3 km away
        TechCandidate far = new TechCandidate(UUID.randomUUID(), "Far Marco", List.of("HVAC"),
                1, LatLng.of(39.0997, -94.5786));   // Kansas City, far

        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(wo("HVAC", "ROUTINE", "MEDIUM", jsId, 9)),
                List.of(near, far),
                sites);

        assertThat(plan.assignments()).hasSize(1);
        assertThat(plan.assignments().get(0).assignedTechUserId()).isEqualTo(near.userId());
        assertThat(plan.assignments().get(0).proximity())
                .isGreaterThan(0.9); // near tech is well within PROXIMITY_FULL_KM
    }

    // ── 5. deterministic across runs ─────────────────────────────────────────────────

    @Test
    void sameInputs_yieldSamePlan_acrossRuns() {
        TechCandidate dana = tech("Dana", List.of("HVAC"));
        TechCandidate marco = tech("Marco", List.of("HVAC", "ELECTRICAL"));
        List<WorkOrder> jobs = List.of(
                wo("HVAC", "EMERGENCY", "LARGE", null, 8),
                wo("ELECTRICAL", "ROUTINE", "SMALL", null, 9),
                wo("HVAC", "URGENT", "MEDIUM", null, 10));

        DispatchPlan p1 = optimizer.optimize(DAY, jobs, List.of(dana, marco), Map.of());
        // Rebuild fresh candidates (the optimize mutates load/anchor) and re-run.
        DispatchPlan p2 = optimizer.optimize(DAY, jobs,
                List.of(tech2(dana), tech2(marco)), Map.of());

        List<UUID> order1 = p1.assignments().stream().map(ProposedAssignment::workOrderId).toList();
        List<UUID> order2 = p2.assignments().stream().map(ProposedAssignment::workOrderId).toList();
        assertThat(order1).isEqualTo(order2);
    }

    private TechCandidate tech2(TechCandidate src) {
        return new TechCandidate(src.userId(), src.displayName(), src.skills(), 0, null);
    }

    // ── 6. rationale present + scores clamped to [0,1] ───────────────────────────────

    @Test
    void everyAssignmentHasRationale_andScoresAreClamped() {
        TechCandidate dana = tech("Dana", List.of("HVAC"));
        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(wo("HVAC", "EMERGENCY", "LARGE", null, 8)),
                List.of(dana),
                Map.of());

        ProposedAssignment a = plan.assignments().get(0);
        assertThat(a.rationale()).isNotBlank();
        assertThat(a.rationale()).contains("confirm");
        for (double v : new double[]{a.score(), a.confidence(), a.skillFit(),
                a.availability(), a.proximity(), a.priority()}) {
            assertThat(v).isBetween(0.0, 1.0);
        }
    }

    // ── 7. empty crew → everything unassigned (never throws) ─────────────────────────

    @Test
    void noTechs_allUnassigned() {
        DispatchPlan plan = optimizer.optimize(DAY,
                List.of(wo("HVAC", "ROUTINE", "SMALL", null, 9)),
                List.of(),
                Map.of());
        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unassigned()).hasSize(1);
        assertThat(plan.unassigned().get(0).unassignedReason()).contains("No technicians");
    }
}
