package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

/**
 * T4 — how one inbound patient message was resolved by the Switchboard, for the deflection analytics
 * ("how many did the AI handle vs hand off?"). PHI-free by construction — a category enum, never content.
 */
public enum SwitchboardDeflectionCategory {

    /** A logistics question the {@link LogisticsIntentHandler} answered from {@link SwitchboardConfig}. */
    LOGISTICS,

    /** A clinical/symptom message the {@link ClinicalTripwireHandler} handed off (no transcript kept). */
    TRIPWIRE,

    /**
     * An unmatched / UNKNOWN message the E2 {@code DefaultHandoffIntentHandler} handed off to staff
     * (recorded by {@link SwitchboardDeflectionRecorder} from the {@code RESPONDER_HANDED_OFF} event,
     * scoped to health-vertical tenants).
     */
    HANDOFF
}
