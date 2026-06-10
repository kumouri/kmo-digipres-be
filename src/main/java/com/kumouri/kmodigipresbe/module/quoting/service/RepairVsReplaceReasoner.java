package com.kumouri.kmodigipresbe.module.quoting.service;

import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.model.Recommendation;
import com.kumouri.kmodigipresbe.module.quoting.model.RepairVsReplace;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * T8 (Home Services "QuoteNow") — the Q3 core: an <strong>explained</strong> repair-vs-replace
 * recommendation. <strong>Pure, deterministic, stateless, total</strong> — no I/O, no model call,
 * never throws. Transparent reasoning (the rationale is always non-blank) is the conversion lever —
 * it disarms pricing distrust.
 *
 * <h2>The decision (deterministic, explainable)</h2>
 * Given the homeowner's {@link QuoteAttributes} and the synthesized repair + replace
 * {@link QuoteRange}s for the matched equipment, with the line's typical lifespan:
 * <ul>
 *   <li><strong>DIAGNOSTIC_VISIT</strong> when there isn't enough to advise — the equipment type is
 *       unknown (the repair range is diagnostic-only), or the age is unknown AND no severe failure is
 *       described. Honest default, never a guess.</li>
 *   <li><strong>REPLACE</strong> when the unit is at/near end of life
 *       ({@code ageYears >= replaceAgeFraction × typicalLifespan}, default 0.7), OR the repair cost
 *       (the repair-range midpoint) is a large fraction of the replacement cost (the replace-range
 *       low) — the classic "a repair on an old unit approaches the cost of a far more efficient new
 *       one." <strong>Surfaces the financing flag.</strong></li>
 *   <li><strong>REPAIR</strong> otherwise — a young unit whose repair is well below replacement.</li>
 * </ul>
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code QuotingAutoConfiguration} when the module is on.
 */
public class RepairVsReplaceReasoner {

    /** At/above this fraction of typical lifespan → lean REPLACE (default 0.7 = 70%). */
    private static final double REPLACE_AGE_FRACTION = 0.70;
    /** Repair-cost-to-replacement ratio at/above which → lean REPLACE (default 0.5 = 50%). */
    private static final double REPAIR_TO_REPLACE_RATIO = 0.50;

    /**
     * Decide repair-vs-replace from the attributes + the matched line's repair/replace ranges + the
     * book (for the line's typical lifespan). The orchestrator passes the synthesized
     * {@code repairRange} and {@code replaceRange} (either may be diagnostic-only when the type didn't
     * match its kind). Never null, never throws.
     */
    public RepairVsReplace decide(PriceBook book, QuoteAttributes attributes,
                                  QuoteRange repairRange, QuoteRange replaceRange) {
        // Not enough to advise: unknown equipment (repair is diagnostic-only) → diagnostic visit.
        if (attributes == null || repairRange == null || repairRange.isDiagnosticOnly()) {
            return diagnostic("We need a closer look to advise repair vs. replace — let's start with a "
                    + "diagnostic visit so a technician can confirm the equipment and the fault.");
        }

        Integer age = attributes.getAgeYears();
        boolean severe = describesSevereFailure(attributes);

        // Age unknown AND no severe symptom → not enough signal to advise; diagnostic visit.
        if (age == null && !severe) {
            return diagnostic("Without the unit's age we can't responsibly call repair vs. replace — a "
                    + "quick diagnostic visit will let the technician confirm the unit's condition.");
        }

        Integer lifespan = typicalLifespan(book, attributes);

        // 1) Age-based end-of-life signal.
        if (age != null && lifespan != null && lifespan > 0
                && age >= (int) Math.ceil(REPLACE_AGE_FRACTION * lifespan)) {
            String why = "At about " + age + " years, this unit is near the end of its typical "
                    + lifespan + "-year life. A repair now often buys little time and costs nearly what "
                    + "a new, far more efficient unit would — so replacement is usually the better value.";
            return replace(why);
        }

        // 2) Repair-cost-to-replacement signal (independent of age — a major component failure).
        Optional<Double> ratio = repairToReplaceRatio(repairRange, replaceRange);
        if (ratio.isPresent() && ratio.get() >= REPAIR_TO_REPLACE_RATIO) {
            String why = "The estimated repair runs about "
                    + Math.round(ratio.get() * 100) + "% of the cost of a new unit. When a repair "
                    + "approaches replacement cost, a new unit (with a fresh warranty and better "
                    + "efficiency) is typically the smarter spend.";
            return replace(why);
        }

        // 3) Otherwise repair.
        StringBuilder why = new StringBuilder("This unit is a good candidate for repair");
        if (age != null) {
            why.append(" — at about ").append(age).append(" years it has useful life left");
        }
        why.append(", and the estimated repair is well below the cost of replacement.");
        return RepairVsReplace.builder()
                .recommendation(Recommendation.REPAIR)
                .rationale(why.toString())
                .financingAvailable(false)
                .build();
    }

    private static RepairVsReplace replace(String rationale) {
        return RepairVsReplace.builder()
                .recommendation(Recommendation.REPLACE)
                .rationale(rationale)
                .financingAvailable(true)   // the moment to offer financing
                .build();
    }

    private static RepairVsReplace diagnostic(String rationale) {
        return RepairVsReplace.builder()
                .recommendation(Recommendation.DIAGNOSTIC_VISIT)
                .rationale(rationale)
                .financingAvailable(false)
                .build();
    }

    /** The midpoint of the repair range as a fraction of the replacement-range low end (if priceable). */
    private static Optional<Double> repairToReplaceRatio(QuoteRange repair, QuoteRange replace) {
        if (replace == null || replace.isDiagnosticOnly()
                || repair.getLow() == null || repair.getHigh() == null
                || replace.getLow() == null || replace.getLow().signum() == 0) {
            return Optional.empty();
        }
        BigDecimal repairMid = repair.getLow().add(repair.getHigh())
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        double ratio = repairMid.divide(replace.getLow(), 6, RoundingMode.HALF_UP).doubleValue();
        return Optional.of(ratio);
    }

    /** The matched repair/replace line's typical lifespan for the attributes' equipment type. */
    private static Integer typicalLifespan(PriceBook book, QuoteAttributes attributes) {
        if (book == null || book.getLineItems() == null || attributes == null
                || attributes.getEquipmentType() == null || attributes.getEquipmentType().isBlank()) {
            return null;
        }
        String needle = attributes.getEquipmentType().trim().toLowerCase();
        // Prefer the REPLACE line's lifespan, else any matching line's.
        Integer fromReplace = null;
        Integer fromAny = null;
        for (PriceBookLineItem line : book.getLineItems()) {
            if (line.getEquipmentType() == null || line.getTypicalLifespanYears() == null) continue;
            String hay = line.getEquipmentType().trim().toLowerCase();
            if (hay.equals(needle) || hay.contains(needle) || needle.contains(hay)) {
                if (line.getJobKind() == JobKind.REPLACE && fromReplace == null) {
                    fromReplace = line.getTypicalLifespanYears();
                }
                if (fromAny == null) {
                    fromAny = line.getTypicalLifespanYears();
                }
            }
        }
        return fromReplace != null ? fromReplace : fromAny;
    }

    private static boolean describesSevereFailure(QuoteAttributes attributes) {
        if (attributes.getFailureMode() == null || attributes.getFailureMode().isBlank()) {
            return false;
        }
        String f = attributes.getFailureMode().toLowerCase();
        // A coarse, deterministic severity heuristic (the book's per-line keywords drive pricing; this
        // only gates whether we have *enough signal* to advise when the age is unknown).
        return f.contains("compressor") || f.contains("heat exchanger") || f.contains("cracked")
                || f.contains("leak") || f.contains("flood") || f.contains("not cooling")
                || f.contains("no heat") || f.contains("not igniting") || f.contains("blowing warm")
                || f.contains("dead") || f.contains("burned") || f.contains("burnt");
    }
}
