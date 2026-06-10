package com.kumouri.kmodigipresbe.module.styleconsult.controller.dto;

import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;

import java.util.List;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — the prospect-facing response to a consult submission (and the office
 * inbox detail). Surfaces the read style attributes + confidence, the recommended services, the
 * <strong>margin-aware</strong> retail recommendations (each carrying the central "your stylist will
 * confirm" guardrail note), and the lifecycle status — never the raw entity / tenant-internal fields.
 *
 * @param consultId             the persisted {@code StyleConsult} id (the accept endpoint takes it)
 * @param styleCategory         the read/typed style category, nullable
 * @param length                the read/typed length, nullable
 * @param texture               the read/typed texture, nullable
 * @param color                 the read/typed color, nullable
 * @param attributeSource       MANUAL or VISION
 * @param confidence            the vision read confidence in [0,1] (1.0 for manual)
 * @param serviceRecommendations the recommended salon services
 * @param retailRecommendations  the margin-ranked retail products (highest margin first)
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
        List<RetailRecommendation> retailRecommendations,
        StyleConsultStatus status,
        UUID bookingId) {

    /** Project a persisted {@link StyleConsult} into the prospect/office response shape. */
    public static StyleConsultResponse from(StyleConsult c) {
        var attrs = c.getAttributes();
        return new StyleConsultResponse(
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
