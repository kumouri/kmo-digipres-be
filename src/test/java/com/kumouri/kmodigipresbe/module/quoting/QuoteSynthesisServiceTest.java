package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 — {@link QuoteSynthesisService} pure range-synthesis unit test (no Docker, fast). Proves the
 * ranges land in expected bands per attributes (age + severe-failure modifiers), the graceful
 * diagnostic-visit fallback when nothing priceable matches, and the <strong>release-blocking
 * guardrail</strong>: every synthesized range carries the estimate disclaimer.
 */
class QuoteSynthesisServiceTest {

    private final QuoteSynthesisService synth = new QuoteSynthesisService();

    private PriceBook book() {
        return PriceBook.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .currency("USD")
                .lineItems(List.of(
                        PriceBookLineItem.builder()
                                .equipmentType("condenser").jobKind(JobKind.REPAIR)
                                .low(new BigDecimal("250")).high(new BigDecimal("1500"))
                                .typicalLifespanYears(15)
                                .agePerYearPct(new BigDecimal("3.0")).ageMaxPct(new BigDecimal("60"))
                                .severeFailurePct(new BigDecimal("40"))
                                .severeFailureKeywords(List.of("blowing warm", "compressor"))
                                .build(),
                        PriceBookLineItem.builder()
                                .equipmentType("condenser").jobKind(JobKind.REPLACE)
                                .low(new BigDecimal("4500")).high(new BigDecimal("7000"))
                                .typicalLifespanYears(15)
                                .agePerYearPct(new BigDecimal("0.5")).ageMaxPct(new BigDecimal("10"))
                                .build()))
                .diagnosticVisitLow(new BigDecimal("89"))
                .diagnosticVisitHigh(new BigDecimal("149"))
                .build();
    }

    @Test
    void repairBand_baseWhenNoModifiers() {
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").build();
        QuoteRange r = synth.synthesizeFor(book(), attrs, JobKind.REPAIR);
        assertThat(r.isDiagnosticOnly()).isFalse();
        assertThat(r.getLow()).isEqualByComparingTo("250.00");
        assertThat(r.getHigh()).isEqualByComparingTo("1500.00");
        assertThat(r.getBasis()).isEqualTo("condenser — repair");
    }

    @Test
    void ageModifier_widensRepairBand() {
        // 10-yr unit, +3%/yr → ×1.30 (under the 60% cap).
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").ageYears(10).build();
        QuoteRange r = synth.synthesizeFor(book(), attrs, JobKind.REPAIR);
        assertThat(r.getLow()).isEqualByComparingTo("325.00");   // 250 × 1.30
        assertThat(r.getHigh()).isEqualByComparingTo("1950.00"); // 1500 × 1.30
    }

    @Test
    void ageModifier_isCapped() {
        // 40-yr unit would be ×2.20 uncapped; the 60% cap holds it at ×1.60.
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").ageYears(40).build();
        QuoteRange r = synth.synthesizeFor(book(), attrs, JobKind.REPAIR);
        assertThat(r.getLow()).isEqualByComparingTo("400.00");   // 250 × 1.60
        assertThat(r.getHigh()).isEqualByComparingTo("2400.00"); // 1500 × 1.60
    }

    @Test
    void severeFailure_bumpsRepairBand() {
        // 0-yr (no age bump) + "blowing warm" severe keyword → +40% additive.
        QuoteAttributes attrs = QuoteAttributes.builder()
                .equipmentType("condenser").ageYears(0).failureMode("AC blowing warm").build();
        QuoteRange r = synth.synthesizeFor(book(), attrs, JobKind.REPAIR);
        assertThat(r.getLow()).isEqualByComparingTo("350.00");   // 250 × 1.40
        assertThat(r.getHigh()).isEqualByComparingTo("2100.00"); // 1500 × 1.40
    }

    @Test
    void ageAndSevere_compound() {
        // 10-yr (×1.30) + severe (+0.40) → ×1.70.
        QuoteAttributes attrs = QuoteAttributes.builder()
                .equipmentType("condenser").ageYears(10).failureMode("compressor failed").build();
        QuoteRange r = synth.synthesizeFor(book(), attrs, JobKind.REPAIR);
        assertThat(r.getLow()).isEqualByComparingTo("425.00");   // 250 × 1.70
        assertThat(r.getHigh()).isEqualByComparingTo("2550.00"); // 1500 × 1.70
    }

    @Test
    void replaceBand_isSeparateLine() {
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").ageYears(12).build();
        QuoteRange r = synth.synthesizeFor(book(), attrs, JobKind.REPLACE);
        assertThat(r.isDiagnosticOnly()).isFalse();
        // 12 × 0.5% = 6% → ×1.06.
        assertThat(r.getLow()).isEqualByComparingTo("4770.00");  // 4500 × 1.06
        assertThat(r.getHigh()).isEqualByComparingTo("7420.00"); // 7000 × 1.06
        assertThat(r.getBasis()).isEqualTo("condenser — replace");
    }

    @Test
    void unknownEquipment_degradesToDiagnosticRange() {
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("spaceship reactor").build();
        QuoteRange r = synth.synthesize(book(), attrs);
        assertThat(r.isDiagnosticOnly()).isTrue();
        assertThat(r.getLow()).isEqualByComparingTo("89.00");
        assertThat(r.getHigh()).isEqualByComparingTo("149.00");
        assertThat(r.getBasis()).isEqualTo("diagnostic visit");
    }

    @Test
    void emptyAttributes_degradesToDiagnosticRange() {
        QuoteRange r = synth.synthesize(book(), QuoteAttributes.builder().build());
        assertThat(r.isDiagnosticOnly()).isTrue();
        assertThat(r.getLow()).isEqualByComparingTo("89.00");
    }

    @Test
    void nullBook_stillProducesADiagnosticRange_neverThrows() {
        QuoteRange r = synth.synthesize(null, QuoteAttributes.builder().equipmentType("condenser").build());
        assertThat(r.isDiagnosticOnly()).isTrue();
        assertThat(r.getLow()).isEqualByComparingTo("89.00"); // the hardcoded fallback
    }

    @Test
    void synthesize_prefersRepairWhenBothMatch() {
        // synthesize() leads with the repair band when a repair line matches.
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").build();
        QuoteRange r = synth.synthesize(book(), attrs);
        assertThat(r.getBasis()).isEqualTo("condenser — repair");
    }

    @Test
    void everyRange_carriesTheEstimateDisclaimer() {
        // The release-blocking guardrail: priced range, diagnostic range, and null-book range all carry it.
        PriceBook b = book();
        QuoteAttributes priced = QuoteAttributes.builder().equipmentType("condenser").ageYears(8).build();
        QuoteAttributes unknown = QuoteAttributes.builder().equipmentType("nope").build();

        assertThat(synth.synthesizeFor(b, priced, JobKind.REPAIR).getEstimateDisclaimer())
                .isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER)
                .isNotBlank();
        assertThat(synth.synthesizeFor(b, priced, JobKind.REPLACE).getEstimateDisclaimer())
                .isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER);
        assertThat(synth.synthesize(b, unknown).getEstimateDisclaimer())
                .isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER);
        assertThat(synth.synthesize(null, priced).getEstimateDisclaimer())
                .isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER);
    }
}
