package com.kumouri.kmodigipresbe.module.stylermatch.model;

/**
 * T12 (Salon "StylerMatch") — the lifecycle of a {@link StylerMatch}. {@code NEW} on submission;
 * {@code BOOKED} once the client accepts a ranked stylist and a real salon {@code Booking} is created
 * (the T9 {@code StyleConsultStatus} two-state shape).
 */
public enum StylerMatchStatus {
    NEW,
    BOOKED
}
