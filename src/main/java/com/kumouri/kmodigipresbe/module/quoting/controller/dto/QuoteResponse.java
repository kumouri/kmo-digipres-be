package com.kumouri.kmodigipresbe.module.quoting.controller.dto;

import com.kumouri.kmodigipresbe.module.quoting.model.AttributeSource;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.Recommendation;
import com.kumouri.kmodigipresbe.module.quoting.model.RepairVsReplace;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — the homeowner-facing response to a quote submission (and the
 * office inbox detail). Surfaces the price RANGE, the mandatory estimate disclaimer, the explained
 * repair-vs-replace recommendation + financing flag, and the read attributes/confidence — never the
 * raw entity / tenant-internal fields.
 *
 * @param quoteId            the persisted {@code QuoteRequest} id (the accept endpoint takes it)
 * @param low                the low end of the estimate
 * @param high               the high end of the estimate
 * @param currency           ISO-4217
 * @param basis              which price-book line / job kind produced the range
 * @param estimateDisclaimer the mandatory "estimate; final price after on-site inspection" copy
 * @param diagnosticOnly     true when this is a flat diagnostic-visit fee (nothing priceable matched)
 * @param recommendation     REPAIR / REPLACE / DIAGNOSTIC_VISIT
 * @param recommendationRationale the explained "why" (always non-blank)
 * @param financingAvailable surfaced on the REPLACE path
 * @param equipmentType      the equipment type used (typed or vision-read), nullable
 * @param attributeSource    MANUAL or VISION
 * @param confidence         the vision read confidence in [0,1] (1.0 for manual)
 */
public record QuoteResponse(
        UUID quoteId,
        BigDecimal low,
        BigDecimal high,
        String currency,
        String basis,
        String estimateDisclaimer,
        boolean diagnosticOnly,
        Recommendation recommendation,
        String recommendationRationale,
        boolean financingAvailable,
        String equipmentType,
        AttributeSource attributeSource,
        double confidence) {

    /** Project a persisted {@link QuoteRequest} into the homeowner/office response shape. */
    public static QuoteResponse from(QuoteRequest q) {
        QuoteRange r = q.getRange();
        RepairVsReplace rvr = q.getRepairVsReplace();
        return new QuoteResponse(
                q.getId(),
                r == null ? null : r.getLow(),
                r == null ? null : r.getHigh(),
                r == null ? null : r.getCurrency(),
                r == null ? null : r.getBasis(),
                r == null ? null : r.getEstimateDisclaimer(),
                r != null && r.isDiagnosticOnly(),
                rvr == null ? null : rvr.getRecommendation(),
                rvr == null ? null : rvr.getRationale(),
                rvr != null && rvr.isFinancingAvailable(),
                q.getAttributes() == null ? null : q.getAttributes().getEquipmentType(),
                q.getAttributes() == null ? null : q.getAttributes().getSource(),
                q.getAttributes() == null ? 1.0 : q.getAttributes().getConfidence());
    }
}
