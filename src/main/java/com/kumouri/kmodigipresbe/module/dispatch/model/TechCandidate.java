package com.kumouri.kmodigipresbe.module.dispatch.model;

import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;

import java.util.List;
import java.util.UUID;

/**
 * T14 (Home "DispatchIQ") — a technician snapshot the {@code DispatchOptimizerService} scores against the
 * day's open work orders. <strong>Not persisted</strong> — the orchestrator
 * ({@code DispatchPlanService}) builds these from the tenant's STAFF {@code User}s + the already-assigned
 * work orders for the day, then passes them into the pure scorer (the {@code WaitlistMatchService.rankCandidates}
 * shape — no repository calls inside the scorer, so it never blocks the event loop).
 *
 * <p>This is a small <em>mutable</em> holder (not a record) because the greedy assignment pass updates the
 * tech's {@link #currentLoad} (each assignment raises their load so the next pick load-balances) and their
 * {@link #anchor} (the location of their last assigned stop, so the next pick routes by proximity). The
 * mutation is confined to a single synchronous pass inside the pure scorer over a freshly-built list — no
 * shared state escapes.
 */
public final class TechCandidate {

    private final UUID userId;
    private final String displayName;
    private final List<String> skills;

    /** Count of work orders already assigned to this tech for the day (the starting availability load). */
    private int currentLoad;

    /**
     * The location to measure travel-proximity from for this tech's next stop — initialised to the
     * coordinates of their first already-assigned stop that day (else {@code null} = no anchor yet), and
     * advanced to each newly-assigned work order's job-site coordinates during the greedy pass.
     */
    private LatLng anchor;

    public TechCandidate(UUID userId, String displayName, List<String> skills,
                         int currentLoad, LatLng anchor) {
        this.userId = userId;
        this.displayName = displayName;
        this.skills = skills == null ? List.of() : List.copyOf(skills);
        this.currentLoad = Math.max(0, currentLoad);
        this.anchor = anchor;
    }

    public UUID userId() {
        return userId;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> skills() {
        return skills;
    }

    public int currentLoad() {
        return currentLoad;
    }

    public LatLng anchor() {
        return anchor;
    }

    /** Record one more assignment to this tech (raises load) and move the routing anchor to its site. */
    public void assignTo(LatLng jobLocation) {
        this.currentLoad += 1;
        if (jobLocation != null) {
            this.anchor = jobLocation;
        }
    }
}
