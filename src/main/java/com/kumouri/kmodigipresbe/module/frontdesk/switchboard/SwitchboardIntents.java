package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;

import java.util.List;

/**
 * T4 (Health "Switchboard AI") — the canonical health front-desk intent vocabulary the E2 responder
 * classifier is constrained to, plus the PHI-forbidding default classifier system prompt and the
 * vertical key.
 *
 * <h2>The vertical key</h2>
 * {@link #VERTICAL} = {@code "health"} — a tenant's {@code ResponderConfig.vertical} must be this for the
 * Switchboard handlers ({@link LogisticsIntentHandler}, {@link ClinicalTripwireHandler}) to claim its
 * inbound messages (each handler's {@code supports(vertical, intent)} checks this).
 *
 * <h2>The intents (logistics-only + the clinical tripwire)</h2>
 * Seven <strong>logistics-only</strong> intents the {@link LogisticsIntentHandler} answers from per-tenant
 * {@link SwitchboardConfig} (never a clinical answer), and one <strong>clinical tripwire</strong> intent
 * ({@link #CLINICAL_SYMPTOM}) the {@link ClinicalTripwireHandler} claims to hand off (no transcript kept).
 * Because each intent is claimed by exactly one handler, the router routes a clinical message to the
 * tripwire BEFORE any logistics handler can see it (the "tripwire before logistics" requirement) — there
 * is no ordering conflict.
 *
 * <h2>The classifier system prompt is the LAST PHI fence, never the only one</h2>
 * {@link #HEALTH_CLASSIFIER_PROMPT} is the per-tenant {@code ResponderConfig.systemPromptOverride} the
 * demo seed + go-live config use. It mirrors {@code HealthFrontDeskExtractionStrategy.SYSTEM_PROMPT}:
 * classify a symptom/clinical message as {@link #CLINICAL_SYMPTOM} and <strong>return an empty
 * {@code extractedSlots} object, never echoing any clinical detail</strong> — so the one model-controlled
 * surface the E2 router persists ({@code ConversationStateService.recordTurn} merges
 * {@code classification.extractedSlots()}) carries no clinical text. The structural fences (the raw body
 * is never persisted — {@code ConversationState} has no body field; the tripwire handler writes only a
 * redaction marker) hold regardless of the model.
 */
public final class SwitchboardIntents {

    /** The {@code ResponderConfig.vertical} a tenant sets to activate the Switchboard handlers. */
    public static final String VERTICAL = "health";

    // ── Logistics-only intents (answered from per-tenant SwitchboardConfig) ──────
    public static final String HOURS = "HOURS";
    public static final String LOCATION = "LOCATION";
    public static final String ACCEPTING_NEW_PATIENTS = "ACCEPTING_NEW_PATIENTS";
    public static final String BOOK_APPOINTMENT = "BOOK_APPOINTMENT";
    public static final String RESCHEDULE = "RESCHEDULE";
    public static final String INTAKE_FORM = "INTAKE_FORM";
    public static final String REVIEW_REQUEST = "REVIEW_REQUEST";

    /** The clinical/symptom tripwire intent — claimed only by {@link ClinicalTripwireHandler}. */
    public static final String CLINICAL_SYMPTOM = "CLINICAL_SYMPTOM";

    /** The seven logistics intents (the {@link LogisticsIntentHandler} support set). */
    public static final List<String> LOGISTICS_INTENTS = List.of(
            HOURS, LOCATION, ACCEPTING_NEW_PATIENTS, BOOK_APPOINTMENT, RESCHEDULE, INTAKE_FORM,
            REVIEW_REQUEST);

    /**
     * The default configured-intent set for a health Switchboard tenant (the demo seed + config-CRUD
     * default). Each carries a natural-language description the classifier prompt surfaces. The clinical
     * tripwire's description is deliberately broad so any symptom/medical message lands on it.
     */
    public static final List<IntentDefinition> DEFAULTS = List.of(
            new IntentDefinition(HOURS,
                    "The patient is asking about the office hours / when the practice is open or closed."),
            new IntentDefinition(LOCATION,
                    "The patient is asking where the office is, the address, directions, or parking."),
            new IntentDefinition(ACCEPTING_NEW_PATIENTS,
                    "The patient is asking whether the practice is accepting new patients."),
            new IntentDefinition(BOOK_APPOINTMENT,
                    "The patient wants to book / schedule / make a new appointment."),
            new IntentDefinition(RESCHEDULE,
                    "The patient wants to reschedule, move, change, or cancel an existing appointment."),
            new IntentDefinition(INTAKE_FORM,
                    "The patient is asking for new-patient paperwork, intake forms, or registration forms."),
            new IntentDefinition(REVIEW_REQUEST,
                    "The patient wants to leave a review, give feedback, or asks where to review the office."),
            new IntentDefinition(CLINICAL_SYMPTOM,
                    "The patient is describing a SYMPTOM, a medical problem, pain, an injury, how they feel, "
                            + "a medication/prescription question, a clinical question, or anything that "
                            + "needs clinical judgement (e.g. \"I have chest pain\", \"my tooth is "
                            + "throbbing\", \"is this rash serious\", \"can I take ibuprofen with...\"). "
                            + "ANY message that is not purely a front-desk logistics question belongs here."));

    /**
     * The PHI-forbidding default classifier system prompt (the per-tenant
     * {@code ResponderConfig.systemPromptOverride} value). The last fence: it constrains the model to one
     * of the configured intent names or {@code UNKNOWN} AND <strong>forbids echoing any clinical detail
     * into {@code extractedSlots}</strong> — for {@link #CLINICAL_SYMPTOM} the model must return an empty
     * {@code extractedSlots} object. Mirrors {@code HealthFrontDeskExtractionStrategy.SYSTEM_PROMPT}.
     */
    public static final String HEALTH_CLASSIFIER_PROMPT =
            "You are the intent classifier for a health-practice front desk's inbound patient SMS (a "
            + "dental, medical, or veterinary office). Classify the patient's message into exactly ONE of "
            + "the intents below, or \"UNKNOWN\" if none clearly fits. Allowed intents:\n"
            + "- HOURS: asking about office hours / when the practice is open.\n"
            + "- LOCATION: asking the address, directions, or parking.\n"
            + "- ACCEPTING_NEW_PATIENTS: asking whether the practice takes new patients.\n"
            + "- BOOK_APPOINTMENT: wants to book/schedule a new appointment.\n"
            + "- RESCHEDULE: wants to reschedule, change, or cancel an existing appointment.\n"
            + "- INTAKE_FORM: asking for new-patient paperwork / intake forms.\n"
            + "- REVIEW_REQUEST: wants to leave a review or give feedback.\n"
            + "- CLINICAL_SYMPTOM: the patient describes a symptom, a medical problem, pain, an injury, how "
            + "they feel, a medication/prescription question, or anything needing clinical judgement. ANY "
            + "message that is not purely a logistics question belongs here.\n"
            + "CRITICAL — you are NOT a clinical system and you MUST NOT echo any clinical detail in your "
            + "answer: do NOT repeat symptoms, conditions, diagnoses, procedures, body parts, medication "
            + "names, dosages, or the reason the patient is unwell. For CLINICAL_SYMPTOM you MUST return an "
            + "EMPTY \"extractedSlots\" object ({}). Respond with ONLY a JSON object, no prose, no code "
            + "fences, of the form: {\"intent\": \"<one allowed intent or UNKNOWN>\", \"confidence\": "
            + "<0.0-1.0>, \"extractedSlots\": {}}. For a logistics intent you MAY put a non-clinical "
            + "logistics detail (e.g. a preferred day) in extractedSlots; for CLINICAL_SYMPTOM or UNKNOWN "
            + "use an empty object. This is a triage hint a human will act on.";

    private SwitchboardIntents() {
    }
}
