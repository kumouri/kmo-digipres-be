package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

/**
 * T4 (Health "Switchboard AI") — the fixed redaction marker used as the {@code Activity.body} for the
 * clinical-message tripwire ({@link ClinicalTripwireHandler}), and the default safe handoff reply.
 *
 * <p>The data-layer PHI fence (mirrors {@code TwilioVoicemailService.TRANSCRIPT_REDACTED_MARKER} from
 * FD-2 fence F2): when a clinical/symptom inbound is detected, the patient's raw words are NEVER stored —
 * the tripwire handler writes only this fixed marker as the {@code Activity.body} (and only PHI-free
 * logistics/category fields on the payload), so a patient saying "I have chest pain" never lands in a
 * stored, queryable record. The raw inbound body is never persisted by the E2 router either
 * ({@code ConversationState} has no body field), so the marker is the only content that survives.
 */
public final class SwitchboardRedaction {

    /** The fixed {@code Activity.body} for a clinical tripwire — never the patient's message. */
    public static final String CLINICAL_MESSAGE_REDACTED_MARKER =
            "(clinical message not retained — care-team callback requested)";

    /** A PHI-free, generic one-line summary for the tripwire callback Activity + staff notify. */
    public static final String TRIPWIRE_SUMMARY =
            "Clinical/medical text message received — care-team callback requested";

    /** The default safe reply texted back to a patient whose message tripped the clinical tripwire. */
    public static final String DEFAULT_SAFE_TRIPWIRE_REPLY =
            "Thanks for reaching out — a member of our care team will call you back as soon as possible. "
            + "If this is a medical emergency, please call 911.";

    private SwitchboardRedaction() {
    }
}
