package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.model.Recommendation;
import com.kumouri.kmodigipresbe.module.quoting.model.RepairVsReplace;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService;
import com.kumouri.kmodigipresbe.module.quoting.service.RepairVsReplaceReasoner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 — {@link RepairVsReplaceReasoner} pure-decision unit test (no Docker, fast). Proves the verdict
 * flips old-vs-new, the financing flag is surfaced on REPLACE, the rationale is always non-blank
 * (explainability is the conversion lever), and the honest DIAGNOSTIC_VISIT default when there isn't
 * enough signal. Uses the real {@link QuoteSynthesisService} to build the input ranges so the
 * repair-cost-to-replacement ratio path is exercised end-to-end.
 */
class RepairVsReplaceReasonerTest {

    private final QuoteSynthesisService synth = new QuoteSynthesisService();
    private final RepairVsReplaceReasoner reasoner = new RepairVsReplaceReasoner();

    /** A condenser book: 15-yr lifespan, modest repair band, ~$4.5–7k replace band. */
    private PriceBook book() {
        return PriceBook.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID()).currency("USD")
                .lineItems(List.of(
                        PriceBookLineItem.builder()
                                .equipmentType("condenser").jobKind(JobKind.REPAIR)
                                .low(new BigDecimal("250")).high(new BigDecimal("1500"))
                                .typicalLifespanYears(15)
                                .agePerYearPct(new BigDecimal("3.0")).ageMaxPct(new BigDecimal("60"))
                                .severeFailurePct(new BigDecimal("40"))
                                .severeFailureKeywords(List.of("compressor", "blowing warm"))
                                .build(),
                        PriceBookLineItem.builder()
                                .equipmentType("condenser").jobKind(JobKind.REPLACE)
                                .low(new BigDecimal("4500")).high(new BigDecimal("7000"))
                                .typicalLifespanYears(15)
                                .agePerYearPct(new BigDecimal("0.5")).ageMaxPct(new BigDecimal("10"))
                                .build()))
                .diagnosticVisitLow(new BigDecimal("89")).diagnosticVisitHigh(new BigDecimal("149"))
                .build();
    }

    private RepairVsReplace decide(QuoteAttributes attrs) {
        PriceBook b = book();
        QuoteRange repair = synth.synthesizeFor(b, attrs, JobKind.REPAIR);
        QuoteRange replace = synth.synthesizeFor(b, attrs, JobKind.REPLACE);
        return reasoner.decide(b, attrs, repair, replace);
    }

    @Test
    void oldUnit_recommendsReplace_withFinancing() {
        // 12 yr of a 15-yr unit → >= 70% of lifespan → REPLACE.
        QuoteAttributes attrs = QuoteAttributes.builder()
                .equipmentType("condenser").ageYears(12).failureMode("blowing warm").build();
        RepairVsReplace r = decide(attrs);
        assertThat(r.getRecommendation()).isEqualTo(Recommendation.REPLACE);
        assertThat(r.isFinancingAvailable()).as("financing surfaced on REPLACE").isTrue();
        assertThat(r.getRationale()).isNotBlank();
    }

    @Test
    void youngUnit_recommendsRepair_noFinancing() {
        // 3 yr of a 15-yr unit, mild symptom → repair band well below replacement → REPAIR.
        QuoteAttributes attrs = QuoteAttributes.builder()
                .equipmentType("condenser").ageYears(3).failureMode("low refrigerant").build();
        RepairVsReplace r = decide(attrs);
        assertThat(r.getRecommendation()).isEqualTo(Recommendation.REPAIR);
        assertThat(r.isFinancingAvailable()).isFalse();
        assertThat(r.getRationale()).isNotBlank();
    }

    @Test
    void unknownEquipment_recommendsDiagnosticVisit() {
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("mystery box").ageYears(20).build();
        RepairVsReplace r = decide(attrs);
        assertThat(r.getRecommendation()).isEqualTo(Recommendation.DIAGNOSTIC_VISIT);
        assertThat(r.isFinancingAvailable()).isFalse();
        assertThat(r.getRationale()).isNotBlank();
    }

    @Test
    void unknownAge_andNoSevereSymptom_recommendsDiagnosticVisit() {
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").build();
        RepairVsReplace r = decide(attrs);
        assertThat(r.getRecommendation()).isEqualTo(Recommendation.DIAGNOSTIC_VISIT);
        assertThat(r.getRationale()).isNotBlank();
    }

    @Test
    void unknownAge_butSevereSymptom_stillAdvises_viaCostRatio() {
        // No age, but a compressor failure → severe → enough signal; the +40% repair band pushes the
        // repair-cost-to-replacement ratio toward (but here likely below) the threshold → REPAIR with
        // a clear rationale (not a diagnostic punt).
        QuoteAttributes attrs = QuoteAttributes.builder()
                .equipmentType("condenser").failureMode("compressor failed").build();
        RepairVsReplace r = decide(attrs);
        assertThat(r.getRecommendation()).isIn(Recommendation.REPAIR, Recommendation.REPLACE);
        assertThat(r.getRecommendation()).isNotEqualTo(Recommendation.DIAGNOSTIC_VISIT);
        assertThat(r.getRationale()).isNotBlank();
    }

    @Test
    void veryOldUnit_atLifespan_recommendsReplace() {
        QuoteAttributes attrs = QuoteAttributes.builder().equipmentType("condenser").ageYears(18).build();
        RepairVsReplace r = decide(attrs);
        assertThat(r.getRecommendation()).isEqualTo(Recommendation.REPLACE);
        assertThat(r.isFinancingAvailable()).isTrue();
    }

    @Test
    void neverThrows_onNullsAndDiagnosticRanges() {
        QuoteRange diag = synth.synthesize(null, QuoteAttributes.builder().build());
        RepairVsReplace r = reasoner.decide(null, QuoteAttributes.builder().build(), diag, diag);
        assertThat(r.getRecommendation()).isEqualTo(Recommendation.DIAGNOSTIC_VISIT);
        assertThat(r.getRationale()).isNotBlank();
    }
}
