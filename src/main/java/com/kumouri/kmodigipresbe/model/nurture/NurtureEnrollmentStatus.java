package com.kumouri.kmodigipresbe.model.nurture;

/**
 * Lifecycle of one contact's {@link NurtureEnrollment} in a campaign (E1 — Nurture / Cadence Engine).
 *
 * <p>Flow: {@code ENROLLED → ACTIVE → } one terminal state.
 * <ul>
 *   <li>{@code ENROLLED} — created by segmentation; not yet touched. (Due immediately — the runner
 *       picks it up and sends step 0.)</li>
 *   <li>{@code ACTIVE} — at least one cadence step processed; {@code nextFireAt} gates the next.</li>
 *   <li>{@code REPLIED} — a positive reply exited the enrollment (pre-booking-link).</li>
 *   <li>{@code BOOKED} — the booking-link SMS was sent after a positive reply (the happy exit).</li>
 *   <li>{@code EXITED} — manually exited, or the campaign was deactivated, or the contact was
 *       unreachable.</li>
 *   <li>{@code OPTED_OUT} — the contact carries the {@code sms-opt-out} tag (STOP) — never touched.</li>
 *   <li>{@code COMPLETED} — ran past the last cadence step without a reply.</li>
 * </ul>
 */
public enum NurtureEnrollmentStatus {
    ENROLLED,
    ACTIVE,
    REPLIED,
    BOOKED,
    EXITED,
    OPTED_OUT,
    COMPLETED;

    /** True for the five terminal states (a positive-reply / runner advance is a no-op on these). */
    public boolean isTerminal() {
        return this == REPLIED || this == BOOKED || this == EXITED
                || this == OPTED_OUT || this == COMPLETED;
    }
}
