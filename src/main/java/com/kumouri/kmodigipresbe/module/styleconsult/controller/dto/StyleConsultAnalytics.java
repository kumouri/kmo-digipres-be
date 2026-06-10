package com.kumouri.kmodigipresbe.module.styleconsult.controller.dto;

import java.math.BigDecimal;

/**
 * T9 (Salon "StyleConsult AI") — S4: the retail-attach analytics funnel for a tenant's style consults.
 * Surfaces how the vision-COMPOSITION funnel converts: consults submitted → consults that produced a
 * retail recommendation → consults booked → the <strong>retail-attach rate</strong> (the share of
 * booked consults that carried a retail recommendation, i.e. the add-on-sale opportunity put in front
 * of a booking client) + the average recommended-retail margin (the revenue lever).
 *
 * @param totalConsults             all consults submitted
 * @param consultsWithRetail        consults that produced ≥1 retail recommendation
 * @param consultsBooked            consults that converted to a booking
 * @param bookedWithRetail          booked consults that had ≥1 retail recommendation
 * @param retailAttachRate          {@code bookedWithRetail / consultsBooked} in [0,1] (0 if none booked)
 * @param bookingRate               {@code consultsBooked / totalConsults} in [0,1] (0 if none)
 * @param avgRecommendedRetailMargin the mean {@code marginAmount} across all recommended retail items
 */
public record StyleConsultAnalytics(
        long totalConsults,
        long consultsWithRetail,
        long consultsBooked,
        long bookedWithRetail,
        double retailAttachRate,
        double bookingRate,
        BigDecimal avgRecommendedRetailMargin) {
}
