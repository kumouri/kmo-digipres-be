package com.kumouri.kmodigipresbe.module.quoting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * T8 (Home Services "QuoteNow") — one priced line in a tenant's {@link PriceBook}, keyed by
 * (equipment type × {@link JobKind}). Embedded in the {@code PriceBook} document (not its own
 * collection) — the book is loaded whole and matched in memory by {@code QuoteSynthesisService}.
 *
 * <h2>The range is the product</h2>
 * Each line carries a base {@code low}/{@code high} band, then two modifiers that
 * {@code QuoteSynthesisService} applies multiplicatively to widen/shift the band from the
 * homeowner's attributes:
 * <ul>
 *   <li>{@link #agePerYearPct} — a per-year-of-age adjustment (e.g. {@code 1.5} = +1.5%/yr on a
 *       repair line: an older unit costs more to repair); applied as
 *       {@code base × (1 + agePerYearPct/100 × ageYears)}, clamped at {@link #ageMaxPct}.</li>
 *   <li>{@link #severeFailurePct} — an additive % bump applied when the homeowner's failure mode
 *       matches one of {@link #severeFailureKeywords} (a "severe" symptom costs more).</li>
 * </ul>
 * All modifiers default to zero/empty — a book with bare low/high bands just returns them verbatim.
 *
 * @param equipmentType        the unit class this line prices (e.g. "condenser", "furnace",
 *                             "water heater"); matched case-insensitively / by substring
 * @param jobKind              REPAIR or REPLACE
 * @param low                  base low end of the band
 * @param high                 base high end of the band
 * @param typicalLifespanYears the unit's typical lifespan in years (the repair-vs-replace age
 *                             threshold); nullable
 * @param agePerYearPct        per-year-of-age band adjustment, percent (default 0)
 * @param ageMaxPct            cap on the cumulative age adjustment, percent (default 0 = uncapped
 *                             when {@code agePerYearPct} is also 0; a positive value caps it)
 * @param severeFailurePct     additive band bump (percent) when a severe failure keyword matches
 *                             (default 0)
 * @param severeFailureKeywords lowercase substrings that mark a severe failure (e.g. "compressor",
 *                             "cracked", "flood"); nullable/empty
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PriceBookLineItem {

    private String equipmentType;
    private JobKind jobKind;

    private BigDecimal low;
    private BigDecimal high;

    private Integer typicalLifespanYears;

    @Builder.Default
    private BigDecimal agePerYearPct = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal ageMaxPct = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal severeFailurePct = BigDecimal.ZERO;

    private java.util.List<String> severeFailureKeywords;
}
