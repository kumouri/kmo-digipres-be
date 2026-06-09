package com.kumouri.kmodigipresbe.module.homeservices.callback;

/**
 * T5 (Home Services "Instant Callback") — the missed-call → callback recovery funnel stage one
 * {@link CallbackFunnelLog} row records. A pure PHI-free counter ledger (the
 * {@code SwitchboardDeflectionCategory} posture).
 *
 * <ul>
 *   <li>{@link #OFFERED} — the callback opt-in SMS was sent to a caller after a home-services voicemail
 *       (written by {@code CallbackOfferSubscriber}).</li>
 *   <li>{@link #ACCEPTED} — the caller replied opting in; a {@link CallbackRequest} was recorded
 *       (written by {@code CallbackIntentHandler}).</li>
 *   <li>{@link #DISPATCHED} — a dispatcher claimed the callback (written by the dispatch endpoint).</li>
 * </ul>
 */
public enum CallbackFunnelStage {
    OFFERED,
    ACCEPTED,
    DISPATCHED
}
