package com.kumouri.kmodigipresbe.module.dispatch.model;

import java.time.Instant;
import java.util.UUID;

/**
 * T14 (Home "DispatchIQ") — one proposed dispatch assignment: an open {@code WorkOrder} matched to the
 * best-fit technician (or surfaced unassigned), with an <strong>explained rationale</strong> + the
 * per-component score breakdown so the dispatcher (and the FE) can see <em>why</em> the optimizer picked
 * this tech for this job. The home-services dispatch twin of the T12 {@code RankedMatch} (same
 * snapshot-plus-rationale-plus-component shape).
 *
 * <p>{@code score} is the composite [0,1] fit of the assigned tech for this work order (0 when
 * unassigned). The four components ({@code skillFit}, {@code availability}, {@code proximity},
 * {@code priority}) are each [0,1] and feed both the composite and the rationale. {@code skillMatched}
 * reflects whether the assigned tech's declared {@code skills} cover the work order's {@code serviceType}
 * (the headline correctness signal). When no tech could be assigned, {@code assignedTechUserId} is
 * {@code null}, {@code score} is 0, and {@code unassignedReason} explains why (e.g. "no available
 * technician has the HVAC skill") — the optimizer surfaces an unstaffable job rather than mis-assigning
 * it to a wrong-skill tech.
 *
 * @param workOrderId        the open work order being dispatched
 * @param workOrderNumber    the human-readable WO number snapshot (nullable on legacy work orders)
 * @param title              the work order's title/label snapshot (nullable)
 * @param serviceType        the free-form skill the job needs (the {@code WorkOrder.serviceType})
 * @param urgency            the urgency signal read from {@code customFields.urgency} (nullable)
 * @param jobValueBand       the value band read from {@code customFields.jobValueBand} (nullable)
 * @param scheduledStart     the work order's scheduled start (nullable)
 * @param jobSiteId          the work order's job site (nullable)
 * @param assignedTechUserId the proposed technician, or {@code null} when unassignable
 * @param assignedTechName   the proposed technician's display-name snapshot (nullable)
 * @param score              the composite fit score in [0,1] (0 when unassigned)
 * @param confidence         how many signals were available to score on, in [0,1]
 * @param skillFit           the skill-match component in [0,1]
 * @param availability       the load-balance/availability component in [0,1]
 * @param proximity          the travel/proximity component in [0,1]
 * @param priority           the urgency/value priority component in [0,1]
 * @param skillMatched       whether the assigned tech's declared skills cover the job's service type
 * @param rationale          the human-readable why (always non-blank)
 * @param unassignedReason   why no tech was assigned (null when {@code assignedTechUserId} is non-null)
 */
public record ProposedAssignment(
        UUID workOrderId,
        String workOrderNumber,
        String title,
        String serviceType,
        String urgency,
        String jobValueBand,
        Instant scheduledStart,
        UUID jobSiteId,
        UUID assignedTechUserId,
        String assignedTechName,
        double score,
        double confidence,
        double skillFit,
        double availability,
        double proximity,
        double priority,
        boolean skillMatched,
        String rationale,
        String unassignedReason) {

    /** True when the optimizer found a technician for this work order. */
    public boolean isAssigned() {
        return assignedTechUserId != null;
    }
}
