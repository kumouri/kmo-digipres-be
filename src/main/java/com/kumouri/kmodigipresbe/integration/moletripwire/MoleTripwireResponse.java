package com.kumouri.kmodigipresbe.integration.moletripwire;

import java.util.UUID;

/**
 * The result of a coverage customer's B2 re-activity tripwire report (Phase 3 — NMM coverage-window
 * automation), returned to the customer's browser/app.
 *
 * <p>Framed as <strong>triage, not truth</strong> (plan §8), exactly like the Phase-2
 * {@link com.kumouri.kmodigipresbe.integration.molevision.MoleTriageResponse}: {@code aboveThreshold}
 * reflects whether the vision model's confidence cleared
 * {@code kmosf.mole-tripwire.confidence-threshold} AND the category is a pest. When above threshold,
 * a re-treatment {@code Milestone} is auto-created on the customer's {@code Project} and Rob is
 * notified — {@code retreatmentMilestoneId} carries that milestone's id (null otherwise). A
 * below-threshold / {@code unsure} report is still stored, but creates no Milestone and is framed
 * "unclear — we'll review". The CRM never auto-charges; Rob always confirms.
 *
 * @param classification        the model's category, lower-case wire form
 *                              ({@code "mole"|"vole"|"gopher"|"none"|"unsure"})
 * @param confidence            the model's confidence in {@code [0.0, 1.0]}
 * @param aboveThreshold        whether this is a confident pest classification (cleared the threshold)
 * @param message               a customer-friendly one-liner (always present)
 * @param attachmentId          the stored photo's Attachment id (never null)
 * @param retreatmentMilestoneId the auto-created re-treatment Milestone id when above threshold;
 *                              null when below threshold / unsure (no Milestone created)
 */
public record MoleTripwireResponse(
        String classification,
        double confidence,
        boolean aboveThreshold,
        String message,
        UUID attachmentId,
        UUID retreatmentMilestoneId) {
}
