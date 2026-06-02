package com.kumouri.kmodigipresbe.integration.molevision;

/**
 * The structured classification a homeowner's photo gets from {@link MoleVisionService}
 * (Phase 2 — NMM "is this a mole?" photo triage, Feature B). Every field is defensively
 * defaulted — a blank/unparseable model answer degrades to {@link #unsure()} rather than
 * failing the ingest (AI is triage, not truth — plan §8; the photo is always stored as an
 * {@code Attachment} so Rob can verify).
 *
 * @param category   the classified category (never null; {@link MoleClassificationCategory#UNSURE}
 *                   on any parse failure)
 * @param confidence the model's stated confidence in {@code [0.0, 1.0]} (clamped; 0.0 when absent)
 * @param rationale  a short model-provided rationale, if any (nullable/blank-tolerant)
 */
public record MoleClassification(
        MoleClassificationCategory category,
        double confidence,
        String rationale) {

    /** An UNSURE, zero-confidence classification — the soft-fallback when extraction fails. */
    public static MoleClassification unsure() {
        return new MoleClassification(MoleClassificationCategory.UNSURE, 0.0, null);
    }

    /** True iff the model classified an actual pest mound (mole/vole/gopher) — not NONE/UNSURE. */
    public boolean isPest() {
        return category == MoleClassificationCategory.MOLE
                || category == MoleClassificationCategory.VOLE
                || category == MoleClassificationCategory.GOPHER;
    }

    /**
     * A one-line human summary for the Activity summary + the notify-Rob message. Built defensively
     * from whatever fields are present.
     */
    public String toSummaryLine() {
        StringBuilder sb = new StringBuilder("Photo triage: ");
        sb.append(category.wire());
        sb.append(" (confidence ").append(String.format("%.2f", confidence)).append(")");
        if (rationale != null && !rationale.isBlank()) {
            sb.append(" — ").append(rationale.trim());
        }
        return sb.toString();
    }
}
