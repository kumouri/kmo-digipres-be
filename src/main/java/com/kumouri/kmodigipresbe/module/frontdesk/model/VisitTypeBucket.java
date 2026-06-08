package com.kumouri.kmodigipresbe.module.frontdesk.model;

/**
 * FrontDesk IQ (FD-1) — the scheduling category of an {@link Appointment}.
 *
 * <p><strong>This is the line FrontDesk IQ deliberately rides up to, and the fence (F1)
 * that keeps it on the PHI-free side.</strong> A visit-type bucket is a <em>scheduling
 * logistics</em> category chosen by front-desk staff at appointment creation — it is
 * NOT a diagnosis, procedure, or chief complaint. The closed enum (no free text) means a
 * staff member cannot encode a clinical detail here, and the no-show scorer consumes only
 * the {@link Enum#ordinal()} of this value as one feature — it never sees, and there is no
 * field on {@link Appointment} that could hold, a diagnosis or procedure (the headline
 * boundary in the FrontDesk IQ plan §0 / D4).
 *
 * <p>The bucket steers internal cadence (e.g. recall vs new-patient) but is
 * <strong>never rendered into outbound patient copy</strong> (fence F3, FD-2).
 *
 * <p>{@code OTHER} / an unrecognized wire value maps to {@link #OTHER} via {@link #fromWire}
 * so an unknown bucket is never rejected and never silently treated as a real category.
 */
public enum VisitTypeBucket {
    NEW_PATIENT,
    RECALL,
    FOLLOW_UP,
    HYGIENE,
    ANNUAL_WELLNESS,
    OTHER;

    /**
     * Lenient parse of a wire string to a bucket. Null/blank/unrecognized → {@link #OTHER}
     * (never an error — an unknown category is logistics noise, not a failure, and must never
     * become a backdoor for free-text clinical content). Case-insensitive.
     */
    public static VisitTypeBucket fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return OTHER;
        }
        try {
            return VisitTypeBucket.valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return OTHER;
        }
    }
}
