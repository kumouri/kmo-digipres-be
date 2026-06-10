package com.kumouri.kmodigipresbe.module.styleconsult.service;

import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultAnalytics;
import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;
import com.kumouri.kmodigipresbe.module.styleconsult.repository.StyleConsultRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — S4: the <strong>retail-attach analytics</strong> read. Aggregates a
 * tenant's {@link StyleConsult}s into the {@link StyleConsultAnalytics} funnel — total consults,
 * consults that produced a retail recommendation, consults booked, the retail-attach rate, and the
 * average recommended-retail margin. A pure DB scan over the tenant's consults (explicit-tenant
 * {@code findByTenantId}); no mutation. Hand-constructed as a {@code @Bean}.
 */
@RequiredArgsConstructor
public class StyleConsultAnalyticsService {

    private final StyleConsultRepository consults;

    /** Compute the retail-attach funnel over all of {@code tenantId}'s style consults. */
    public Mono<StyleConsultAnalytics> analytics(UUID tenantId) {
        return consults.findByTenantId(tenantId).collectList()
                .map(StyleConsultAnalyticsService::aggregate);
    }

    static StyleConsultAnalytics aggregate(List<StyleConsult> all) {
        long total = all.size();
        long withRetail = 0;
        long booked = 0;
        long bookedWithRetail = 0;
        BigDecimal marginSum = BigDecimal.ZERO;
        long marginCount = 0;

        for (StyleConsult c : all) {
            boolean hasRetail = c.getRetailRecommendations() != null
                    && !c.getRetailRecommendations().isEmpty();
            boolean isBooked = c.getStatus() == StyleConsultStatus.BOOKED;
            if (hasRetail) withRetail++;
            if (isBooked) booked++;
            if (isBooked && hasRetail) bookedWithRetail++;
            if (c.getRetailRecommendations() != null) {
                for (RetailRecommendation r : c.getRetailRecommendations()) {
                    if (r != null && r.getMarginAmount() != null) {
                        marginSum = marginSum.add(r.getMarginAmount());
                        marginCount++;
                    }
                }
            }
        }

        double retailAttachRate = booked == 0 ? 0.0 : (double) bookedWithRetail / booked;
        double bookingRate = total == 0 ? 0.0 : (double) booked / total;
        BigDecimal avgMargin = marginCount == 0
                ? BigDecimal.ZERO
                : marginSum.divide(BigDecimal.valueOf(marginCount), 2, RoundingMode.HALF_UP);

        return new StyleConsultAnalytics(total, withRetail, booked, bookedWithRetail,
                round(retailAttachRate), round(bookingRate), avgMargin);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
