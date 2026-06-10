package com.kumouri.kmodigipresbe.module.quoting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * T8 (Home Services "QuoteNow") — the explained repair-vs-replace recommendation produced by
 * {@code RepairVsReplaceReasoner} from the homeowner's {@link QuoteAttributes} + the synthesized
 * repair/replace {@link QuoteRange}s. Deterministic + <strong>explained</strong> (the rationale is
 * always non-blank) — transparent reasoning disarms pricing distrust (the conversion lever).
 *
 * @param recommendation     REPAIR / REPLACE / DIAGNOSTIC_VISIT
 * @param rationale          the human-readable "why" (age vs lifespan, repair-cost vs replacement,
 *                           efficiency) — always non-blank
 * @param financingAvailable surfaced TRUE on the REPLACE path (a new-unit purchase is the moment to
 *                           offer financing); false otherwise
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RepairVsReplace {

    private Recommendation recommendation;
    private String rationale;

    @Builder.Default
    private boolean financingAvailable = false;
}
