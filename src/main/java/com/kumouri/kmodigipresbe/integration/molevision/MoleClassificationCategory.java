package com.kumouri.kmodigipresbe.integration.molevision;

/**
 * The set of categories the {@link MoleVisionService} vision model may return for a homeowner's
 * photo (Phase 2 — NMM "is this a mole?" photo triage, Feature B).
 *
 * <p>{@code MOLE}/{@code VOLE}/{@code GOPHER} are the three pest mounds Rob distinguishes;
 * {@code NONE} means the photo shows no mound/pest sign; {@code UNSURE} is the soft-fallback the
 * defensive parser degrades to whenever the model's answer is blank, unparseable, or an
 * unrecognized label (AI is triage, not truth — plan §8; an {@code UNSURE} is recorded and
 * surfaced as "unclear — a human will confirm", never auto-charged).
 *
 * <p>The wire JSON the prompt asks for is lower-case ({@code "mole"|"vole"|"gopher"|"none"|
 * "unsure"}); {@link #fromWire(String)} maps it case-insensitively and is the ONLY place the
 * wire vocabulary is assumed (the adapter boundary).
 */
public enum MoleClassificationCategory {
    MOLE,
    VOLE,
    GOPHER,
    NONE,
    UNSURE;

    /**
     * Maps the model's lower-case wire label to a category. Null/blank/unrecognized → {@link #UNSURE}
     * (never throws — the defensive-parse contract).
     */
    public static MoleClassificationCategory fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return UNSURE;
        }
        return switch (wire.trim().toLowerCase()) {
            case "mole" -> MOLE;
            case "vole" -> VOLE;
            case "gopher" -> GOPHER;
            case "none" -> NONE;
            default -> UNSURE;
        };
    }

    /** The lower-case wire label (for payloads / summaries). */
    public String wire() {
        return name().toLowerCase();
    }
}
