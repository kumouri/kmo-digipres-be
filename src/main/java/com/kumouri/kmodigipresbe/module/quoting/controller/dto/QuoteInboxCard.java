package com.kumouri.kmodigipresbe.module.quoting.controller.dto;

import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.model.Recommendation;
import com.kumouri.kmodigipresbe.module.quoting.model.RepairVsReplace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — one row in the office quote-inbox list. A lean projection of a
 * {@link QuoteRequest}: who, what equipment, the range, the recommendation, and the status — enough
 * for a dispatcher to triage at a glance. Never the raw entity / tenant-internal fields.
 *
 * @param quoteId        the {@code QuoteRequest} id (the detail endpoint takes it)
 * @param contactId      the homeowner Contact (nullable)
 * @param contactPhone   the homeowner phone (nullable)
 * @param equipmentType  the equipment type (typed or vision-read), nullable
 * @param low            the low end of the estimate
 * @param high           the high end of the estimate
 * @param currency       ISO-4217
 * @param recommendation REPAIR / REPLACE / DIAGNOSTIC_VISIT (nullable)
 * @param diagnosticOnly true when a flat diagnostic-visit fee
 * @param status         NEW / ACCEPTED / BOOKED / DECLINED
 * @param createdAt      when the homeowner submitted
 */
public record QuoteInboxCard(
        UUID quoteId,
        UUID contactId,
        String contactPhone,
        String equipmentType,
        BigDecimal low,
        BigDecimal high,
        String currency,
        Recommendation recommendation,
        boolean diagnosticOnly,
        QuoteStatus status,
        Instant createdAt) {

    public static QuoteInboxCard from(QuoteRequest q) {
        QuoteRange r = q.getRange();
        RepairVsReplace rvr = q.getRepairVsReplace();
        return new QuoteInboxCard(
                q.getId(),
                q.getContactId(),
                q.getContactPhone(),
                q.getAttributes() == null ? null : q.getAttributes().getEquipmentType(),
                r == null ? null : r.getLow(),
                r == null ? null : r.getHigh(),
                r == null ? null : r.getCurrency(),
                rvr == null ? null : rvr.getRecommendation(),
                r != null && r.isDiagnosticOnly(),
                q.getStatus(),
                q.getCreatedAt());
    }
}
