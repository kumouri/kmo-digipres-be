package com.kumouri.kmodigipresbe.service.ai.vision;

/**
 * The structured result of a generic Anthropic-vision classify call ({@link AiVisionService}).
 * The vertical-agnostic counterpart to the mole-specific
 * {@code com.kumouri.kmodigipresbe.integration.molevision.MoleClassification}: where that type
 * maps the wire label into a fixed {@code MoleClassificationCategory} enum, this keeps the label
 * as a free-form {@code String} so any vertical (home-services trade triage, equipment-plate
 * recognition, etc.) can reuse the same transport with its own taxonomy.
 *
 * <p>Every field is defensively defaulted — a blank/unparseable model answer degrades to
 * {@link #unsure()} rather than failing the caller (AI is triage, not truth). Callers that need a
 * typed category map {@link #label()} themselves at their own adapter boundary (the
 * {@code MoleClassificationCategory.fromWire(...)} pattern).
 *
 * @param label      the model's classification label (the {@code "classification"} JSON key), or
 *                   {@code null} when the answer was blank/unparseable
 * @param confidence the model's stated confidence in {@code [0.0, 1.0]} (clamped; 0.0 when absent)
 * @param rationale  a short model-provided rationale, if any (nullable/blank-tolerant)
 */
public record VisionClassification(
        String label,
        double confidence,
        String rationale) {

    /** An empty, zero-confidence classification — the soft-fallback when classification fails. */
    public static VisionClassification unsure() {
        return new VisionClassification(null, 0.0, null);
    }

    /** True iff the model returned a non-blank label (i.e. this is not a soft-fallback). */
    public boolean hasLabel() {
        return label != null && !label.isBlank();
    }

    /** Alias for {@link #hasLabel()} — reads naturally at a caller's branch. */
    public boolean isPresent() {
        return hasLabel();
    }
}
