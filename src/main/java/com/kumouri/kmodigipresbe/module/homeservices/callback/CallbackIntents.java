package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;

import java.util.List;

/**
 * T5 (Home Services "Instant Callback") — the canonical home-callback intent vocabulary the E2 responder
 * classifier is constrained to, plus the default classifier system prompt and the vertical key. The
 * {@code SwitchboardIntents} precedent.
 *
 * <h2>The vertical key</h2>
 * {@link #VERTICAL} = {@code "home"} — a tenant's {@code ResponderConfig.vertical} must be this for the
 * {@link CallbackIntentHandler} to claim its inbound messages ({@code supports("home", intent)}).
 *
 * <h2>The intents</h2>
 * Two callback intents — {@link #CALLBACK_NOW} ("call me now / ASAP") and {@link #CALLBACK_SCHEDULED}
 * ("call me at &lt;time&gt; / in &lt;N&gt; minutes"). Both are claimed only by {@link CallbackIntentHandler};
 * any other message classifies as UNKNOWN and falls to the E2 {@code DefaultHandoffIntentHandler}
 * (unchanged). The classifier prompt asks the model to put the stated time/window into a
 * {@code preferredTime} slot for {@link #CALLBACK_SCHEDULED} (the responder persists
 * {@code extractedSlots} via {@code ConversationStateService.recordTurn}).
 */
public final class CallbackIntents {

    /** The {@code ResponderConfig.vertical} a tenant sets to activate the callback handler. */
    public static final String VERTICAL = "home";

    /** "Call me now / ASAP / right away" — an immediate callback. */
    public static final String CALLBACK_NOW = "CALLBACK_NOW";

    /** "Call me at &lt;time&gt; / in &lt;N&gt; minutes / tomorrow morning" — a scheduled callback. */
    public static final String CALLBACK_SCHEDULED = "CALLBACK_SCHEDULED";

    /** The slot key the classifier fills with the caller's stated time/window (for SCHEDULED). */
    public static final String SLOT_PREFERRED_TIME = "preferredTime";

    /** The callback intents the {@link CallbackIntentHandler} support set. */
    public static final List<String> CALLBACK_INTENTS = List.of(CALLBACK_NOW, CALLBACK_SCHEDULED);

    /**
     * The default configured-intent set for a home-callback tenant (the demo seed + config default). Each
     * carries a natural-language description the classifier prompt surfaces.
     */
    public static final List<IntentDefinition> DEFAULTS = List.of(
            new IntentDefinition(CALLBACK_NOW,
                    "The caller wants a callback right now / immediately / as soon as possible / ASAP, "
                            + "with no specific time stated (e.g. \"now\", \"call me back\", \"yes call "
                            + "me\", \"asap\")."),
            new IntentDefinition(CALLBACK_SCHEDULED,
                    "The caller names a time or window for the callback (e.g. \"in 30 minutes\", \"after "
                            + "5pm\", \"tomorrow morning\", \"at 2pm\", \"this afternoon\")."));

    /**
     * The default classifier system prompt (the per-tenant {@code ResponderConfig.systemPromptOverride}
     * value the demo seed + go-live config use). Constrains the model to one of the configured intent
     * names or {@code UNKNOWN}, and for {@link #CALLBACK_SCHEDULED} asks it to put the stated time/window
     * verbatim into {@code extractedSlots.preferredTime}. The {@code SwitchboardIntents.HEALTH_CLASSIFIER_PROMPT}
     * shape.
     */
    public static final String HOME_CALLBACK_CLASSIFIER_PROMPT =
            "You are the intent classifier for a home-services contractor's inbound SMS replies to a "
            + "\"want a callback?\" text we sent after a missed call. Classify the caller's reply into "
            + "exactly ONE of the intents below, or \"UNKNOWN\" if none clearly fits. Allowed intents:\n"
            + "- CALLBACK_NOW: the caller wants a callback now / immediately / ASAP, with no specific "
            + "time (e.g. \"now\", \"yes\", \"call me\", \"asap\", \"right away\").\n"
            + "- CALLBACK_SCHEDULED: the caller names a time or window (e.g. \"in 30 minutes\", \"after "
            + "5pm\", \"tomorrow morning\", \"at 2pm\", \"this afternoon\").\n"
            + "Respond with ONLY a JSON object, no prose, no code fences, of the form: {\"intent\": "
            + "\"<one allowed intent or UNKNOWN>\", \"confidence\": <0.0-1.0>, \"extractedSlots\": {}}. "
            + "For CALLBACK_SCHEDULED you MUST put the caller's stated time/window verbatim in "
            + "extractedSlots as {\"preferredTime\": \"<the words the caller used>\"}; for CALLBACK_NOW "
            + "or UNKNOWN use an empty object. This is a triage hint a dispatcher will act on.";

    private CallbackIntents() {
    }
}
