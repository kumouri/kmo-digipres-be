package com.kumouri.kmodigipresbe.module.homeservices.callback;

/**
 * T5 (Home Services "Instant Callback") — the lifecycle of one materialized {@link CallbackRequest}.
 *
 * <p>{@link #REQUESTED} → the caller opted in and a card is on the dispatcher's revenue-ranked queue;
 * {@link #DISPATCHED} → a dispatcher claimed it (the recovery-funnel "dispatched" stage);
 * {@link #COMPLETED} / {@link #CANCELLED} are terminal (reserved for the FE leg's outcome actions).
 */
public enum CallbackStatus {
    REQUESTED,
    DISPATCHED,
    COMPLETED,
    CANCELLED
}
