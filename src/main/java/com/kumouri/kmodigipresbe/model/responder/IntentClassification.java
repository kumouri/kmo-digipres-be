package com.kumouri.kmodigipresbe.model.responder;

import java.util.Map;

/**
 * E2 — the result of classifying one inbound free-text message against a tenant's configured intent set
 * (the {@link com.kumouri.kmodigipresbe.service.responder.InboundIntentClassifier} output).
 *
 * <p><strong>Best-effort by construction</strong> — AI is triage, not truth. A budget-exhausted, upstream,
 * or parse failure degrades to {@link #unknown()} ({@code intent="UNKNOWN"}, {@code confidence=0.0},
 * empty slots) rather than throwing or dropping the message; the router then routes {@code UNKNOWN} to the
 * default handoff (a human follows up). The {@code intent} is always one of the tenant's configured
 * {@link IntentDefinition#name()} values or the sentinel {@code "UNKNOWN"}.
 *
 * @param intent         the classified intent name (a configured name or {@code "UNKNOWN"})
 * @param confidence     the model's confidence, clamped to {@code [0.0, 1.0]}
 * @param extractedSlots optional structured slots the model pulled from the message (never null)
 */
public record IntentClassification(String intent, double confidence, Map<String, String> extractedSlots) {

    /** The sentinel intent name for an unclassifiable / degraded message. */
    public static final String UNKNOWN = "UNKNOWN";

    public IntentClassification {
        extractedSlots = extractedSlots == null ? Map.of() : Map.copyOf(extractedSlots);
    }

    /** True iff this is the {@code UNKNOWN} sentinel (the router routes these to the default handoff). */
    public boolean isUnknown() {
        return UNKNOWN.equals(intent);
    }

    /** The degraded fallback — never throws, never drops the message; the router hands off. */
    public static IntentClassification unknown() {
        return new IntentClassification(UNKNOWN, 0.0, Map.of());
    }
}
