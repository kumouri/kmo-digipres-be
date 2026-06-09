package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

/**
 * T7 (Health "RescheduleFlow") — the fill-funnel stage a {@link RescheduleFillLog} row records. A pure
 * logistics counter category (the {@code SwitchboardDeflectionCategory} posture) — it says only which step
 * of the cancel → offer → claim → filled funnel happened, never any clinical/patient content (PHI-free).
 *
 * <ul>
 *   <li>{@code CANCELLATION} — a frontdesk {@code Appointment} cancelled and kicked off a gap-fill.</li>
 *   <li>{@code OFFER} — a gap-fill issued at least one ranked offer for a freed slot.</li>
 *   <li>{@code CLAIM} — an inbound YES won a freed slot (the atomic claim resolved a winner).</li>
 *   <li>{@code FILLED} — a winning claim materialized a real PHI-free replacement {@code Appointment}.</li>
 * </ul>
 */
public enum RescheduleFillEvent {
    CANCELLATION,
    OFFER,
    CLAIM,
    FILLED
}
