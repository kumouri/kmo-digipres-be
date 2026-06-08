package com.kumouri.kmodigipresbe.module.realestate.model;

/**
 * Real Estate Concierge (RE-1) — the typed category of a single {@link ListingDisclosure} line/section.
 *
 * <p>Used both as a coarse routing label and as the {@code title}/{@code disclosureType} metadata stamped
 * on the disclosure's embedding vector (RE-1 §3 — the disclosure <strong>text</strong> is the grounding
 * corpus; the type is surfaced verbatim in the citation, e.g. "Answered from: BASEMENT — '…'"). Open by
 * design: anything that does not fit a specific category is {@link #GENERAL}.
 */
public enum DisclosureType {
    ROOF,
    FOUNDATION,
    BASEMENT,
    SYSTEMS_HVAC,
    ELECTRICAL,
    PLUMBING,
    WATER,
    PEST,
    LEAD_PAINT,
    FLOOD,
    HOA,
    GENERAL;

    /** Defensive parse of a wire string into a type — unknown/blank → {@link #GENERAL} (never throws). */
    public static DisclosureType fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return GENERAL;
        }
    }
}
