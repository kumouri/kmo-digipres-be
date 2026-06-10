package com.kumouri.kmodigipresbe.module.styleconsult.model;

/**
 * T9 (Salon "StyleConsult AI") — the lifecycle of a {@link StyleConsult}. A submitted consult is
 * {@code NEW} (recommendations generated, awaiting the prospect's accept); accepting it books a real
 * salon {@code Booking} and the consult becomes {@code BOOKED}. Terminal-happy; there is no decline
 * state (an un-accepted consult simply stays NEW in the office inbox).
 */
public enum StyleConsultStatus {
    NEW,
    BOOKED
}
