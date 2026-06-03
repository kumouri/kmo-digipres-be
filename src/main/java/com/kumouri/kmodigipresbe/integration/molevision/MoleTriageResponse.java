package com.kumouri.kmodigipresbe.integration.molevision;

/**
 * The public-facing result of a photo-triage submission (Phase 2 — NMM "is this a mole?" photo
 * triage, Feature B), returned to the homeowner's browser/app.
 *
 * <p>Framed as <strong>triage, not truth</strong> (plan §8): {@code aboveThreshold} reflects
 * whether the model's confidence cleared {@code kmosf.mole-triage.confidence-threshold} AND the
 * category is a pest. Below-threshold or {@code UNSURE} submissions are still recorded and still
 * returned, with {@code message} phrased as "possible/unclear — a human will confirm". The CRM
 * never auto-charges on this — Rob always reviews.
 *
 * @param classification the model's category, lower-case wire form
 *                       ({@code "mole"|"vole"|"gopher"|"none"|"unsure"})
 * @param confidence     the model's confidence in {@code [0.0, 1.0]}
 * @param aboveThreshold whether this is a confident pest classification (cleared the threshold)
 * @param message        a homeowner-friendly one-liner (always present)
 * @param attachmentId   the stored photo's Attachment id (so the FE can reference it; never null)
 */
public record MoleTriageResponse(
        String classification,
        double confidence,
        boolean aboveThreshold,
        String message,
        java.util.UUID attachmentId) {
}
