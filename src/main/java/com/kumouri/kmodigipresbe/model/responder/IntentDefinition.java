package com.kumouri.kmodigipresbe.model.responder;

/**
 * E2 — one configured intent the {@link com.kumouri.kmodigipresbe.service.responder.InboundIntentClassifier}
 * may emit for a tenant. The {@code name} is the stable handler-routing key (e.g. {@code "SCHEDULE_VISIT"},
 * {@code "PRICING_QUESTION"}, {@code "CALLBACK_REQUEST"}); the {@code description} is the natural-language
 * hint the classifier prompt carries so the model can recognize the intent in free text.
 *
 * <p>An embedded value record on {@link ResponderConfig}. The classifier is told the tenant's configured
 * intent set + descriptions and is constrained to emit one of those names (or {@code UNKNOWN}); a label
 * the model returns that is not in the configured set degrades to {@code UNKNOWN} (defensive parse).
 *
 * @param name        the stable, upper-snake intent key (non-blank; the handler routing key)
 * @param description a short natural-language description of when this intent applies
 */
public record IntentDefinition(String name, String description) {
}
