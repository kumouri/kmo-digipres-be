package com.kumouri.kmodigipresbe.module.quoting.model;

/**
 * T8 (Home Services "QuoteNow") — the repair-vs-replace verdict from {@code RepairVsReplaceReasoner}.
 *
 * <ul>
 *   <li>{@code REPAIR} — fix the existing unit (young, repair cost well below replacement).</li>
 *   <li>{@code REPLACE} — replace the unit (old, repair cost approaching replacement, efficiency
 *       gains) — the path that surfaces the financing flag.</li>
 *   <li>{@code DIAGNOSTIC_VISIT} — not enough information (no age / unknown equipment / complex
 *       failure) to advise from a photo; recommend a paid diagnostic visit. The honest default.</li>
 * </ul>
 */
public enum Recommendation {
    REPAIR,
    REPLACE,
    DIAGNOSTIC_VISIT
}
