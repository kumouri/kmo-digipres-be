package com.kumouri.kmodigipresbe.module.styleconsult.controller.dto;

import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — the <strong>prospect-facing</strong> response to a consult submission
 * (the public intake + accept endpoints). Surfaces the read style attributes + confidence, the
 * recommended services, the retail recommendations (each carrying the central "your stylist will confirm"
 * guardrail note), and the lifecycle status — never the raw entity / tenant-internal fields.
 *
 * <h2>Security fix AI-03 — no wholesale cost / per-product margin to anonymous prospects</h2>
 * The retail products are projected into the prospect-safe {@link PublicRetailRecommendation} view, which
 * deliberately <strong>drops</strong> the salon's {@code cost} (wholesale unit cost) and {@code marginAmount}
 * (per-product profit) fields. The margin-ranking still happens server-side (the products arrive
 * highest-margin-first), but a competitor submitting one anonymous consult can no longer read the salon's
 * wholesale cost + markup on its top-margin retail line. The full margin-bearing
 * {@link RetailRecommendation} stays available only on the authenticated staff read surface
 * ({@link StyleConsultStaffResponse}).
 *
 * @param consultId             the persisted {@code StyleConsult} id (the accept endpoint takes it)
 * @param styleCategory         the read/typed style category, nullable
 * @param length                the read/typed length, nullable
 * @param texture               the read/typed texture, nullable
 * @param color                 the read/typed color, nullable
 * @param attributeSource       MANUAL or VISION
 * @param confidence            the vision read confidence in [0,1] (1.0 for manual)
 * @param serviceRecommendations the recommended salon services
 * @param retailRecommendations  the margin-ranked retail products (highest margin first), cost/margin
 *                               <strong>redacted</strong> for the prospect (AI-03)
 * @param status                NEW or BOOKED
 * @param bookingId             the salon Booking created on accept (null until booked)
 */
public record StyleConsultResponse(
        UUID consultId,
        String styleCategory,
        String length,
        String texture,
        String color,
        StyleAttributeSource attributeSource,
        double confidence,
        List<ServiceRecommendation> serviceRecommendations,
        List<PublicRetailRecommendation> retailRecommendations,
        StyleConsultStatus status,
        UUID bookingId) {

    /**
     * A single recommended retail product as exposed to the <strong>anonymous prospect</strong> —
     * the margin-ranked {@link RetailRecommendation} with the salon-confidential {@code cost} and
     * {@code marginAmount} stripped (security fix AI-03). Keeps only what a prospect should see:
     * the product identity, its retail price, and the why.
     *
     * @param productId the catalog {@code Product} id
     * @param sku       the product SKU snapshot (nullable)
     * @param name      the product name snapshot
     * @param price     the retail unit price snapshot (nullable)
     * @param rationale why this product was suggested (always includes the stylist-confirm note)
     */
    public record PublicRetailRecommendation(
            UUID productId,
            String sku,
            String name,
            BigDecimal price,
            String rationale) {

        /** Projects a margin-bearing {@link RetailRecommendation} into the prospect-safe shape. */
        public static PublicRetailRecommendation from(RetailRecommendation r) {
            return new PublicRetailRecommendation(
                    r.getProductId(), r.getSku(), r.getName(), r.getPrice(), r.getRationale());
        }
    }

    /** Project a persisted {@link StyleConsult} into the prospect/public response shape (cost/margin redacted). */
    public static StyleConsultResponse from(StyleConsult c) {
        var attrs = c.getAttributes();
        List<RetailRecommendation> retail = c.getRetailRecommendations() == null
                ? List.of() : c.getRetailRecommendations();
        return new StyleConsultResponse(
                c.getId(),
                attrs == null ? null : attrs.getStyleCategory(),
                attrs == null ? null : attrs.getLength(),
                attrs == null ? null : attrs.getTexture(),
                attrs == null ? null : attrs.getColor(),
                attrs == null ? null : attrs.getSource(),
                attrs == null ? 1.0 : attrs.getConfidence(),
                c.getServiceRecommendations() == null ? List.of() : c.getServiceRecommendations(),
                retail.stream().map(PublicRetailRecommendation::from).toList(),
                c.getStatus(),
                c.getBookingId());
    }
}
