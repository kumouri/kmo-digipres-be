package com.kumouri.kmodigipresbe.module.frontdesk.model;

/**
 * FrontDesk IQ (FD-1) — the logistics lifecycle of an {@link Appointment}.
 *
 * <p>A pure scheduling-status enum, the health analogue of the salon
 * {@code BookingStatus} (FD-1 D1). It carries <strong>no clinical meaning</strong> —
 * it says only where the appointment sits in the front-desk workflow, never anything
 * about why the patient is being seen. {@code NO_SHOW}/{@code COMPLETED} are the two
 * training labels the no-show scorer learns from (NO_SHOW=1, COMPLETED=0);
 * {@code CANCELLED} is excluded from training (a cancel is not a no-show);
 * {@code SCHEDULED}/{@code CONFIRMED} are the future appointments that get scored.
 */
public enum AppointmentStatus {
    SCHEDULED,
    CONFIRMED,
    COMPLETED,
    NO_SHOW,
    CANCELLED
}
