package com.kumouri.kmodigipresbe.module.quoting.model;

/**
 * T8 (Home Services "QuoteNow") — provenance of the {@link QuoteAttributes} a quote was synthesized
 * from. {@code MANUAL} = the homeowner typed them on the form (or no photo was sent); {@code VISION}
 * = read off a photo by the shared {@code AiVisionService.extract}. Vision is best-effort: a budget /
 * upstream / parse failure degrades to {@code MANUAL} (or an empty attribute set), so the quote is
 * always produced — AI is triage, not truth.
 */
public enum AttributeSource {
    MANUAL,
    VISION
}
