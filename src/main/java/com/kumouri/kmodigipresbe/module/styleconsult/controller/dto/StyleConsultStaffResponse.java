package com.kumouri.kmodigipresbe.module.styleconsult.controller.dto;

import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;

import java.util.List;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — the <strong>staff-facing</strong> consult detail, returned only by the
 * authenticated office read surface ({@code GET /styleconsult/consults/{id}}). Identical to the
 * prospect-facing {@link StyleConsultResponse} except that the retail recommendations are the full
 * margin-bearing {@link RetailRecommendation} (carrying the salon's {@code cost} + per-product
 * {@code marginAmount}). This is the deliberate counterpart to security fix AI-03: the salon's
 * coordinator (who is authenticated and owns the data) sees the margin that drives the ranking, while the
 * anonymous prospect on the public endpoint does not.
 *
 * @param consultId             the persisted {@code StyleConsult} id
 * @param styleCategory         the read/typed style category, nullable
 * @param length                the read/typed length, nullable
 * @param texture               the read/typed texture, nullable
 * @param color                 the read/typed color, nullable
 * @param attributeSource       MANUAL or VISION
 * @param confidence            the vision read confidence in [0,1] (1.0 for manual)
 * @param serviceRecommendations the recommended salon services
 * @param retailRecommendations  the margin-ranked retail products (highest margin first), cost + margin
 *                               <strong>included</strong> (staff-only)
 * @param status                NEW or BOOKED
 * @param bookingId             the salon Booking created on accept (null until booked)
 */
public record StyleConsultStaffResponse(
        UUID consultId,
        String styleCategory,
        String length,
        String texture,
        String color,
        StyleAttributeSource attributeSource,
        double confidence,
        List<ServiceRecommendation> serviceRecommendations,
        List<RetailRecommendation> retailRecommendations,
        StyleConsultStatus status,
        UUID bookingId) {

    /** Project a persisted {@link StyleConsult} into the full staff detail shape (margin retained). */
    public static StyleConsultStaffResponse from(StyleConsult c) {
        var attrs = c.getAttributes();
        return new StyleConsultStaffResponse(
                c.getId(),
                attrs == null ? null : attrs.getStyleCategory(),
                attrs == null ? null : attrs.getLength(),
                attrs == null ? null : attrs.getTexture(),
                attrs == null ? null : attrs.getColor(),
                attrs == null ? null : attrs.getSource(),
                attrs == null ? 1.0 : attrs.getConfidence(),
                c.getServiceRecommendations() == null ? List.of() : c.getServiceRecommendations(),
                c.getRetailRecommendations() == null ? List.of() : c.getRetailRecommendations(),
                c.getStatus(),
                c.getBookingId());
    }
}
