package com.kumouri.kmodigipresbe.module.quoting.service;

import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * T8 (Home Services "QuoteNow") — the Q1 core: turns a homeowner's {@link QuoteAttributes} into a
 * defensible price {@link QuoteRange} against the tenant's {@link PriceBook}. <strong>Pure,
 * deterministic, stateless, total</strong> — no I/O, no model call, never throws; the orchestrator
 * loads the book and hands it in.
 *
 * <h2>The range is the product — never a single number</h2>
 * Match the best {@link PriceBookLineItem} for the attributes (by equipment type + {@link JobKind}),
 * apply the line's age + severe-failure modifiers to its base low/high band, and return a
 * {@link QuoteRange}. When nothing priceable matches (unknown/blank equipment type, or an empty
 * book), degrade gracefully to the book's flat diagnostic-visit fee band ({@code diagnosticOnly}) —
 * a quote is ALWAYS produced, never an error.
 *
 * <h2>The wrong-number-liability fence — the mandatory disclaimer</h2>
 * EVERY range this service returns carries {@link #ESTIMATE_DISCLAIMER}, set centrally here (never by
 * a caller) so it can never be omitted. A release-blocking IT asserts it is always present.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code QuotingAutoConfiguration} when the module is
 * enabled (not component-scanned) so no bean exists when {@code kmosf.modules.quoting} is off.
 */
public class QuoteSynthesisService {

    /** The mandatory estimate disclaimer stamped on EVERY synthesized range (the liability fence). */
    public static final String ESTIMATE_DISCLAIMER =
            "This is an estimate based on the information provided. Your final price will be "
            + "confirmed after an on-site inspection.";

    private static final String DEFAULT_CURRENCY = "USD";

    /**
     * Synthesize the REPLACE-or-REPAIR-appropriate range for the given attributes. The choice of
     * which {@link JobKind} to lead with is the reasoner's job; this method exposes both via
     * {@link #synthesizeFor(PriceBook, QuoteAttributes, JobKind)} and a convenience that returns the
     * repair band (the cheaper "what would a fix cost" default the homeowner sees first). The
     * orchestrator typically calls both and lets {@code RepairVsReplaceReasoner} decide which to
     * headline.
     *
     * @return the repair band when a repair line matches, else the replace band, else a diagnostic
     *         range — always non-null, always carrying the disclaimer
     */
    public QuoteRange synthesize(PriceBook book, QuoteAttributes attributes) {
        QuoteRange repair = synthesizeFor(book, attributes, JobKind.REPAIR);
        if (repair != null && !repair.isDiagnosticOnly()) {
            return repair;
        }
        QuoteRange replace = synthesizeFor(book, attributes, JobKind.REPLACE);
        if (replace != null && !replace.isDiagnosticOnly()) {
            return replace;
        }
        return diagnosticRange(book);
    }

    /**
     * Synthesize the band for a specific {@link JobKind}. Returns the modifier-adjusted band when a
     * matching line exists, else the diagnostic-visit range ({@code diagnosticOnly=true}). Never
     * null, never throws.
     */
    public QuoteRange synthesizeFor(PriceBook book, QuoteAttributes attributes, JobKind jobKind) {
        if (book == null) {
            return diagnosticRange(null);
        }
        Optional<PriceBookLineItem> match = matchLine(book.getLineItems(), attributes, jobKind);
        if (match.isEmpty()) {
            return diagnosticRange(book);
        }
        PriceBookLineItem line = match.get();
        if (line.getLow() == null || line.getHigh() == null) {
            return diagnosticRange(book);
        }
        BigDecimal multiplier = ageMultiplier(line, attributes)
                .add(severeFailureBump(line, attributes));
        BigDecimal low = scale(line.getLow().multiply(multiplier));
        BigDecimal high = scale(line.getHigh().multiply(multiplier));
        // Defensive: keep low <= high after rounding.
        if (low.compareTo(high) > 0) {
            BigDecimal tmp = low;
            low = high;
            high = tmp;
        }
        String currency = book.getCurrency() != null ? book.getCurrency() : DEFAULT_CURRENCY;
        return QuoteRange.builder()
                .low(low)
                .high(high)
                .currency(currency)
                .basis(basisLabel(line))
                .estimateDisclaimer(ESTIMATE_DISCLAIMER)
                .diagnosticOnly(false)
                .build();
    }

    /**
     * Match the best line for the attributes + job kind: an exact (case-insensitive) equipment-type
     * match wins; else a substring match either way (the homeowner's "ac" matches a "condenser"
     * line only if the book line names it so — substring is tolerant but conservative). Returns
     * empty when the equipment type is blank or nothing matches.
     */
    private Optional<PriceBookLineItem> matchLine(List<PriceBookLineItem> lines,
                                                  QuoteAttributes attributes, JobKind jobKind) {
        if (lines == null || lines.isEmpty() || attributes == null
                || attributes.getEquipmentType() == null
                || attributes.getEquipmentType().isBlank()) {
            return Optional.empty();
        }
        String needle = attributes.getEquipmentType().trim().toLowerCase();
        // 1) exact type + jobKind
        for (PriceBookLineItem line : lines) {
            if (line.getJobKind() == jobKind && typeEquals(line, needle)) {
                return Optional.of(line);
            }
        }
        // 2) substring type + jobKind
        for (PriceBookLineItem line : lines) {
            if (line.getJobKind() == jobKind && typeContains(line, needle)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static boolean typeEquals(PriceBookLineItem line, String needleLower) {
        return line.getEquipmentType() != null
                && line.getEquipmentType().trim().equalsIgnoreCase(needleLower);
    }

    private static boolean typeContains(PriceBookLineItem line, String needleLower) {
        if (line.getEquipmentType() == null) return false;
        String hay = line.getEquipmentType().trim().toLowerCase();
        return hay.contains(needleLower) || needleLower.contains(hay);
    }

    /**
     * The cumulative age multiplier for a line: {@code 1 + (agePerYearPct/100 × ageYears)}, capped at
     * {@code 1 + ageMaxPct/100} when {@code ageMaxPct > 0}. Returns 1.0 when age or the per-year rate
     * is unknown/zero — a book with no age modifier returns its base band verbatim.
     */
    private static BigDecimal ageMultiplier(PriceBookLineItem line, QuoteAttributes attributes) {
        BigDecimal one = BigDecimal.ONE;
        if (attributes == null || attributes.getAgeYears() == null) {
            return one;
        }
        BigDecimal perYear = line.getAgePerYearPct();
        if (perYear == null || perYear.signum() == 0) {
            return one;
        }
        int age = Math.max(0, attributes.getAgeYears());
        BigDecimal pct = perYear.multiply(BigDecimal.valueOf(age))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal cap = line.getAgeMaxPct();
        if (cap != null && cap.signum() > 0) {
            BigDecimal capFrac = cap.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            if (pct.compareTo(capFrac) > 0) {
                pct = capFrac;
            }
        }
        return one.add(pct);
    }

    /**
     * The additive severe-failure bump (as a fractional add to the multiplier): {@code
     * severeFailurePct/100} when the homeowner's failure mode contains any of the line's severe
     * keywords, else 0. Returns 0 when no keywords/failure mode are set.
     */
    private static BigDecimal severeFailureBump(PriceBookLineItem line, QuoteAttributes attributes) {
        if (attributes == null || attributes.getFailureMode() == null
                || attributes.getFailureMode().isBlank()
                || line.getSevereFailurePct() == null || line.getSevereFailurePct().signum() == 0
                || line.getSevereFailureKeywords() == null
                || line.getSevereFailureKeywords().isEmpty()) {
            return BigDecimal.ZERO;
        }
        String failure = attributes.getFailureMode().toLowerCase();
        for (String kw : line.getSevereFailureKeywords()) {
            if (kw != null && !kw.isBlank() && failure.contains(kw.trim().toLowerCase())) {
                return line.getSevereFailurePct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
    }

    /** The flat diagnostic-visit fallback range (always carries the disclaimer, {@code diagnosticOnly=true}). */
    private QuoteRange diagnosticRange(PriceBook book) {
        BigDecimal low = book != null && book.getDiagnosticVisitLow() != null
                ? book.getDiagnosticVisitLow() : new BigDecimal("89");
        BigDecimal high = book != null && book.getDiagnosticVisitHigh() != null
                ? book.getDiagnosticVisitHigh() : new BigDecimal("149");
        String currency = book != null && book.getCurrency() != null ? book.getCurrency() : DEFAULT_CURRENCY;
        return QuoteRange.builder()
                .low(scale(low))
                .high(scale(high))
                .currency(currency)
                .basis("diagnostic visit")
                .estimateDisclaimer(ESTIMATE_DISCLAIMER)
                .diagnosticOnly(true)
                .build();
    }

    private static String basisLabel(PriceBookLineItem line) {
        String type = line.getEquipmentType() == null ? "service" : line.getEquipmentType().trim();
        String kind = line.getJobKind() == JobKind.REPLACE ? "replace" : "repair";
        return type + " — " + kind;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
