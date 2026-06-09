package com.kumouri.kmodigipresbe.module.homeservices.callback;

/**
 * T5 (Home Services "Instant Callback") — whether the caller asked for an immediate callback or named a
 * future time/window. Determined from the E2-classified intent ({@code CALLBACK_NOW} → {@link #IMMEDIATE},
 * {@code CALLBACK_SCHEDULED} → {@link #SCHEDULED}).
 */
public enum CallbackMode {
    /** "Call me now / ASAP." */
    IMMEDIATE,
    /** "Call me at &lt;time&gt; / in &lt;N&gt; minutes / tomorrow morning." */
    SCHEDULED
}
