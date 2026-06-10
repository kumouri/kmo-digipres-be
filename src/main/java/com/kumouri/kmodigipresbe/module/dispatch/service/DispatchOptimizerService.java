package com.kumouri.kmodigipresbe.module.dispatch.service;

import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchPlan;
import com.kumouri.kmodigipresbe.module.dispatch.model.ProposedAssignment;
import com.kumouri.kmodigipresbe.module.dispatch.model.TechCandidate;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * T14 (Home "DispatchIQ") — the <strong>pure, deterministic, explainable</strong> dispatch optimizer.
 * Given a day's open {@link WorkOrder}s + the available {@link TechCandidate}s + the job-site coordinate
 * map, it proposes a (work order → best-fit technician) assignment that maximizes fit — skill match +
 * load-balanced availability + travel proximity + urgency/value priority — and returns a {@link DispatchPlan}
 * whose {@link ProposedAssignment}s each carry a <strong>rationale + a composite score + a per-component
 * breakdown</strong>.
 *
 * <p><strong>Not an LLM call</strong> — like the T12 {@code StylerMatchScoringService} and the T9
 * {@code StyleRecommendationService} / CF-1 {@code NoShowRiskScoringService} deterministic scoring, the
 * ranking is a transparent weighted sum + a greedy priority-first assignment that is test-stable and
 * defensible. Pure / total / stateless: no repository calls inside the optimizer — the orchestrator
 * ({@code DispatchPlanService}) loads the data and passes the lists in (the {@code WaitlistMatchService}
 * shape), so this never blocks the event loop (the caller still runs it on {@code boundedElastic}).
 *
 * <h2>The per-(work order, tech) composite score (each component in [0,1])</h2>
 * <ol>
 *   <li><strong>Skill fit</strong> ({@link #WEIGHT_SKILL}) — the headline correctness signal: does the
 *       tech's declared {@code skills} cover the work order's {@code serviceType}? A keyword/exact match
 *       &rArr; 1.0; a tech with NO declared skills scores {@link #SKILL_NEUTRAL} (eligible-but-uncertain,
 *       ranked never excluded); a tech WITH declared skills that do not overlap scores {@link #SKILL_MISMATCH}
 *       (low) and is below the {@link #SKILL_ELIGIBILITY_FLOOR}, so they are <em>not</em> assigned that job.</li>
 *   <li><strong>Availability</strong> ({@link #WEIGHT_AVAILABILITY}) — load-balancing: {@code 1 -
 *       min(load, LOAD_CAP)/LOAD_CAP} over the tech's current same-day assigned-work-order count. A free
 *       tech scores 1.0; a saturated tech scores 0. This is what spreads the day's jobs across the crew.</li>
 *   <li><strong>Proximity</strong> ({@link #WEIGHT_PROXIMITY}) — travel: the straight-line haversine from
 *       the tech's routing anchor (their last assigned stop that day) to the work order's job-site
 *       coordinates, normalised by {@link #PROXIMITY_FULL_KM}. No anchor or missing coordinates &rArr; a
 *       neutral {@link #PROXIMITY_NEUTRAL} (the board's missing-coords fallback shape). <em>Straight-line
 *       distance is a proxy; a real routing API for drive-time is a documented go-live swap.</em></li>
 *   <li><strong>Priority</strong> ({@link #WEIGHT_PRIORITY}) — the urgency/value boost from the work
 *       order's {@code customFields.urgency} (EMERGENCY &gt; URGENT &gt; ROUTINE) and {@code jobValueBand}
 *       (LARGE &gt; MEDIUM &gt; SMALL). Identical for every candidate tech of the same job, so it does not
 *       change <em>which</em> tech wins a job — it raises the job's composite so the dispatcher sees urgent
 *       jobs scored higher; the <em>assignment order</em> (below) is what gets urgent jobs the best tech.</li>
 * </ol>
 *
 * <h2>The greedy assignment (the headline behaviour)</h2>
 * Work orders are assigned <strong>priority-first</strong> — sorted EMERGENCY before ROUTINE, then high
 * value first, then earliest {@code scheduledStart} — so an urgent job claims the best-fit free tech before
 * a routine one does. For each work order in that order, the optimizer picks the highest-composite tech
 * <em>whose skill fit clears the {@link #SKILL_ELIGIBILITY_FLOOR}</em> (a tech with declared skills that do
 * not match is ineligible; a no-declared-skills tech is eligible at neutral), assigns it, then raises that
 * tech's load and advances their routing anchor (so the next pick load-balances and routes). A work order
 * with <strong>no eligible tech</strong> is surfaced <strong>unassigned with a reason</strong> — never
 * mis-assigned to a wrong-skill tech. Every proposed assignment (and every unassigned) carries a non-blank
 * rationale. Ties are broken deterministically (tech displayName, then id) so the plan is stable across runs.
 */
@Slf4j
public class DispatchOptimizerService {

    // ── component weights (sum to 1.0) ──────────────────────────────────────────
    static final double WEIGHT_SKILL = 0.45;
    static final double WEIGHT_AVAILABILITY = 0.30;
    static final double WEIGHT_PROXIMITY = 0.15;
    static final double WEIGHT_PRIORITY = 0.10;

    /** Skill-fit for a tech with NO declared skills (eligible-but-uncertain; ranked, never excluded). */
    static final double SKILL_NEUTRAL = 0.30;
    /** Skill-fit for a tech WITH declared skills that do not overlap the job's service type (ineligible). */
    static final double SKILL_MISMATCH = 0.10;
    /**
     * Skill-fit a candidate must reach to be assignable to a job. Set above {@link #SKILL_MISMATCH} and at
     * (not above) {@link #SKILL_NEUTRAL}, so a declared-skills mismatch is excluded while a no-declared-skills
     * tech (neutral) remains eligible to fill an otherwise-unstaffable job.
     */
    static final double SKILL_ELIGIBILITY_FLOOR = SKILL_NEUTRAL;

    /** Availability load cap — a tech at/over this many same-day jobs scores 0 availability. */
    static final int LOAD_CAP = 6;

    /** Distance (km) at/under which proximity scores ~1.0; it decays linearly to 0 at ~3× this. */
    static final double PROXIMITY_FULL_KM = 8.0;
    /** Proximity when there is no routing anchor or the job/anchor has no coordinates. */
    static final double PROXIMITY_NEUTRAL = 0.5;

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Compute the proposed dispatch {@link DispatchPlan} for {@code date}. Pure + deterministic.
     *
     * @param date         the UTC day the plan covers (echoed onto the plan)
     * @param openOrders   the day's open (assignable) work orders — terminal ones must be excluded by the caller
     * @param techs        the available technicians (with skills + starting load + routing anchor)
     * @param jobSites     job-site id → {@link JobSite} (for the proximity coordinates); may be partial/empty
     * @return the plan: priority-first assignments + the unstaffable work orders + summary metrics
     */
    public DispatchPlan optimize(LocalDate date,
                                 List<WorkOrder> openOrders,
                                 List<TechCandidate> techs,
                                 Map<java.util.UUID, JobSite> jobSites) {
        List<WorkOrder> orders = openOrders == null ? List.of() : new ArrayList<>(openOrders);
        List<TechCandidate> candidates = techs == null ? new ArrayList<>() : new ArrayList<>(techs);
        Map<java.util.UUID, JobSite> sites = jobSites == null ? Map.of() : jobSites;

        // Assign priority-first: EMERGENCY/high-value/earliest claims the best free eligible tech first.
        orders.sort(priorityOrder());

        List<ProposedAssignment> assignments = new ArrayList<>();
        List<ProposedAssignment> unassigned = new ArrayList<>();

        for (WorkOrder wo : orders) {
            if (wo == null) {
                continue;
            }
            LatLng jobLoc = coordsOf(wo, sites);
            TechCandidate best = null;
            double bestComposite = -1.0;
            double bestSkill = 0;
            double bestAvail = 0;
            double bestProx = 0;
            double bestPriority = priority(wo);

            // Deterministic candidate order so ties resolve stably (displayName then id).
            candidates.sort(candidateTiebreak());
            for (TechCandidate t : candidates) {
                double skill = skillFit(t, wo.getServiceType());
                if (skill < SKILL_ELIGIBILITY_FLOOR) {
                    continue; // declared-skills mismatch → not eligible for this job
                }
                double avail = availability(t);
                double prox = proximity(t, jobLoc);
                double priority = bestPriority;
                double composite = clamp(WEIGHT_SKILL * skill
                        + WEIGHT_AVAILABILITY * avail
                        + WEIGHT_PROXIMITY * prox
                        + WEIGHT_PRIORITY * priority);
                if (composite > bestComposite) {
                    bestComposite = composite;
                    best = t;
                    bestSkill = skill;
                    bestAvail = avail;
                    bestProx = prox;
                }
            }

            if (best == null) {
                unassigned.add(unassignable(wo, candidates));
            } else {
                boolean skillMatched = skillMatched(best, wo.getServiceType());
                ProposedAssignment pa = new ProposedAssignment(
                        wo.getId(), wo.getWorkOrderNumber(), wo.getTitle(), wo.getServiceType(),
                        urgency(wo), jobValueBand(wo), wo.getScheduledStart(), wo.getJobSiteId(),
                        best.userId(), best.displayName(),
                        round(bestComposite), round(confidence(wo, jobLoc)),
                        round(bestSkill), round(bestAvail), round(bestProx), round(bestPriority),
                        skillMatched,
                        rationale(wo, best, bestSkill, bestAvail, bestProx, skillMatched),
                        null);
                assignments.add(pa);
                // Commit the assignment to this tech so the next job load-balances + routes off it.
                best.assignTo(jobLoc);
            }
        }

        int open = assignments.size() + unassigned.size();
        long matched = assignments.stream().filter(ProposedAssignment::skillMatched).count();
        double skillMatchRate = assignments.isEmpty() ? 0.0 : (double) matched / assignments.size();
        double avgFit = assignments.isEmpty() ? 0.0
                : assignments.stream().mapToDouble(ProposedAssignment::score).average().orElse(0.0);

        return new DispatchPlan(date, assignments, unassigned, open, assignments.size(),
                unassigned.size(), round(skillMatchRate), round(avgFit));
    }

    // ── skill fit ───────────────────────────────────────────────────────────────

    /**
     * [0,1] skill fit of a tech for a job's {@code serviceType}. Keyword/exact overlap between the tech's
     * declared {@code skills} and the service type &rArr; high; no declared skills &rArr; {@link #SKILL_NEUTRAL}
     * (eligible-but-uncertain); declared skills with no overlap &rArr; {@link #SKILL_MISMATCH} (ineligible).
     */
    double skillFit(TechCandidate t, String serviceType) {
        List<String> skills = t.skills();
        if (skills == null || skills.isEmpty()) {
            return SKILL_NEUTRAL; // no declared skills → neutral, fillable but uncertain
        }
        Set<String> want = tokenize(serviceType);
        if (want.isEmpty()) {
            return SKILL_NEUTRAL; // job has no declared service type → skill is not a discriminator
        }
        Set<String> have = new HashSet<>();
        for (String s : skills) {
            have.addAll(tokenize(s));
        }
        int hits = 0;
        for (String kw : want) {
            if (have.contains(kw)) {
                hits++;
            }
        }
        if (hits == 0) {
            return SKILL_MISMATCH; // has skills but none match → ineligible (below the floor)
        }
        double share = (double) hits / want.size();
        // Any hit floors above neutral so a matching tech always beats a no-declared-skills tech; capped 1.0.
        return clamp(SKILL_NEUTRAL + (1.0 - SKILL_NEUTRAL) * Math.min(1.0, share + 0.34 * hits));
    }

    /** True iff the tech has at least one declared skill overlapping the job's service type. */
    boolean skillMatched(TechCandidate t, String serviceType) {
        List<String> skills = t.skills();
        if (skills == null || skills.isEmpty()) {
            return false;
        }
        Set<String> want = tokenize(serviceType);
        if (want.isEmpty()) {
            return false;
        }
        Set<String> have = new HashSet<>();
        for (String s : skills) {
            have.addAll(tokenize(s));
        }
        for (String kw : want) {
            if (have.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ── availability (load balance) ───────────────────────────────────────────────

    double availability(TechCandidate t) {
        int load = Math.max(0, t.currentLoad());
        return clamp(1.0 - (double) Math.min(load, LOAD_CAP) / LOAD_CAP);
    }

    // ── proximity (travel) ────────────────────────────────────────────────────────

    double proximity(TechCandidate t, LatLng jobLoc) {
        LatLng anchor = t.anchor();
        if (anchor == null || jobLoc == null) {
            return PROXIMITY_NEUTRAL; // no anchor / no coords → neutral (the board's missing-coords shape)
        }
        double km = haversineKm(anchor, jobLoc);
        if (km <= PROXIMITY_FULL_KM) {
            return 1.0;
        }
        // Linear decay from 1.0 at PROXIMITY_FULL_KM to 0.0 at 3× it.
        double span = 2.0 * PROXIMITY_FULL_KM;
        return clamp(1.0 - (km - PROXIMITY_FULL_KM) / span);
    }

    // ── priority (urgency / value) ──────────────────────────────────────────────────

    /** [0,1] urgency+value boost from the work order's customFields (identical for every candidate tech). */
    double priority(WorkOrder wo) {
        double u = urgencyWeight(urgency(wo));   // 0..1
        double v = valueWeight(jobValueBand(wo)); // 0..1
        // Urgency dominates; value is a secondary nudge.
        return clamp(0.7 * u + 0.3 * v);
    }

    private static double urgencyWeight(String urgency) {
        if (urgency == null) {
            return 0.34; // unspecified → mid-low
        }
        return switch (urgency.trim().toUpperCase(Locale.US)) {
            case "EMERGENCY" -> 1.0;
            case "URGENT" -> 0.7;
            case "ROUTINE", "LOW" -> 0.2;
            default -> 0.34;
        };
    }

    private static double valueWeight(String band) {
        if (band == null) {
            return 0.34;
        }
        return switch (band.trim().toUpperCase(Locale.US)) {
            case "LARGE" -> 1.0;
            case "MEDIUM" -> 0.6;
            case "SMALL" -> 0.3;
            default -> 0.34;
        };
    }

    // ── confidence ───────────────────────────────────────────────────────────────

    /** Fraction of the discriminating signals (service type, location, urgency/value) present on the job. */
    double confidence(WorkOrder wo, LatLng jobLoc) {
        int present = 0;
        if (wo.getServiceType() != null && !wo.getServiceType().isBlank()) {
            present++;
        }
        if (jobLoc != null) {
            present++;
        }
        if (urgency(wo) != null || jobValueBand(wo) != null) {
            present++;
        }
        return present / 3.0;
    }

    // ── rationale ─────────────────────────────────────────────────────────────────

    private String rationale(WorkOrder wo, TechCandidate t, double skill, double avail, double prox,
                             boolean skillMatched) {
        StringBuilder sb = new StringBuilder();
        String name = t.displayName() != null ? t.displayName() : "This technician";
        String svc = wo.getServiceType() != null && !wo.getServiceType().isBlank()
                ? wo.getServiceType().trim() : null;

        // Skill.
        if (skillMatched && svc != null) {
            sb.append(name).append(" is skilled in ").append(svc).append(". ");
        } else if (t.skills() == null || t.skills().isEmpty()) {
            sb.append(name).append(" is a general technician (no declared skills). ");
        } else {
            sb.append(name).append(" can take this on. ");
        }

        // Priority surfaced for urgent/high-value jobs.
        String urg = urgency(wo);
        if (urg != null && ("EMERGENCY".equalsIgnoreCase(urg) || "URGENT".equalsIgnoreCase(urg))) {
            sb.append("Prioritised — ").append(urg.toUpperCase(Locale.US)).append(" job. ");
        }

        // Availability.
        if (avail >= 0.99) {
            sb.append("Currently free. ");
        } else if (avail <= 0.01) {
            sb.append("Fully loaded today — confirm capacity. ");
        } else {
            sb.append("Has room on today's route. ");
        }

        // Proximity (only when meaningfully near/far, not neutral).
        if (prox >= 0.99) {
            sb.append("Close to their current route. ");
        } else if (prox <= 0.15) {
            sb.append("A longer drive from their other stops. ");
        }

        sb.append("Dispatcher confirms before the job is assigned.");
        return sb.toString();
    }

    private ProposedAssignment unassignable(WorkOrder wo, List<TechCandidate> candidates) {
        String svc = wo.getServiceType() != null && !wo.getServiceType().isBlank()
                ? wo.getServiceType().trim() : null;
        String reason;
        boolean anyDeclaredSkilled = candidates.stream()
                .anyMatch(t -> t.skills() != null && !t.skills().isEmpty());
        if (candidates.isEmpty()) {
            reason = "No technicians are available to dispatch.";
        } else if (svc != null && anyDeclaredSkilled) {
            reason = "No available technician has the " + svc + " skill — assign manually or update tech skills.";
        } else {
            reason = "No eligible technician could be matched — review manually.";
        }
        return new ProposedAssignment(
                wo.getId(), wo.getWorkOrderNumber(), wo.getTitle(), wo.getServiceType(),
                urgency(wo), jobValueBand(wo), wo.getScheduledStart(), wo.getJobSiteId(),
                null, null,
                0.0, round(confidence(wo, coordsOf(wo, Map.of()))),
                0.0, 0.0, 0.0, round(priority(wo)),
                false,
                reason,
                reason);
    }

    // ── ordering ────────────────────────────────────────────────────────────────────

    /** Priority-first work-order order: highest urgency, then highest value, then earliest start, then id. */
    private Comparator<WorkOrder> priorityOrder() {
        return Comparator
                .comparingDouble((WorkOrder wo) -> urgencyWeight(urgency(wo))).reversed()
                .thenComparing(Comparator.comparingDouble((WorkOrder wo) -> valueWeight(jobValueBand(wo))).reversed())
                .thenComparing(wo -> wo.getScheduledStart(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(wo -> wo.getId() == null ? "" : wo.getId().toString());
    }

    private Comparator<TechCandidate> candidateTiebreak() {
        return Comparator
                .comparing((TechCandidate t) -> t.displayName() == null ? "" : t.displayName())
                .thenComparing(t -> t.userId() == null ? "" : t.userId().toString());
    }

    // ── customFields readers (urgency / jobValueBand — the T5 callback signals) ──────

    static String urgency(WorkOrder wo) {
        return customString(wo, "urgency");
    }

    static String jobValueBand(WorkOrder wo) {
        return customString(wo, "jobValueBand");
    }

    private static String customString(WorkOrder wo, String key) {
        if (wo == null || wo.getCustomFields() == null) {
            return null;
        }
        Object v = wo.getCustomFields().get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ── geometry ─────────────────────────────────────────────────────────────────

    private static LatLng coordsOf(WorkOrder wo, Map<java.util.UUID, JobSite> jobSites) {
        if (wo.getJobSiteId() == null) {
            return null;
        }
        JobSite js = jobSites.get(wo.getJobSiteId());
        if (js == null) {
            return null;
        }
        LatLng loc = js.getLocation();
        if (loc == null || loc.getCoordinates() == null || loc.getCoordinates().length < 2) {
            return null;
        }
        return loc;
    }

    private static double haversineKm(LatLng a, LatLng b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.longitude() - a.longitude());
        double sinDLat = Math.sin(dLat / 2);
        double sinDLng = Math.sin(dLng / 2);
        double h = sinDLat * sinDLat + Math.cos(lat1) * Math.cos(lat2) * sinDLng * sinDLng;
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Lowercase word tokens of length ≥ 2 (so "MOLE_TRAPPING" → {mole, trapping}; "HVAC" → {hvac}). */
    private static Set<String> tokenize(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        for (String tok : s.toLowerCase(Locale.US).split("[^a-z0-9]+")) {
            if (tok.length() >= 2) {
                out.add(tok);
            }
        }
        return out;
    }

    private static double clamp(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
