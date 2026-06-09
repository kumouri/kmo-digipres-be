package com.kumouri.kmodigipresbe.module.homeservices.callback;

/**
 * T5 (Home Services "Instant Callback") — the generic, business-neutral default copy used when a tenant's
 * {@link CallbackConfig} field is blank. The {@code SwitchboardRedaction.DEFAULT_SAFE_TRIPWIRE_REPLY}
 * posture: a safe default so the feature works before a tenant customizes, but the real copy lives in the
 * per-tenant config (never hardcoded business copy).
 */
public final class CallbackCopy {

    /** The opt-in offer SMS texted to a caller after a home-services voicemail. */
    public static final String DEFAULT_OFFER_MESSAGE =
            "Sorry we missed your call! Reply NOW for a callback as soon as we're free, or text a time "
            + "(e.g. \"in 30 min\" or \"after 5pm\") and we'll call you then.";

    /** The confirmation reply for an immediate callback request. */
    public static final String DEFAULT_IMMEDIATE_CONFIRM =
            "Got it — we'll call you back as soon as we're free. Thanks for your patience!";

    /** The confirmation reply for a scheduled callback request. */
    public static final String DEFAULT_SCHEDULED_CONFIRM =
            "Got it — we'll call you back at the time you asked. Thanks!";

    private CallbackCopy() {
    }
}
