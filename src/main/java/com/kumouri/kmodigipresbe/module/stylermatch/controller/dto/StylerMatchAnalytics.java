package com.kumouri.kmodigipresbe.module.stylermatch.controller.dto;

/**
 * T12 (Salon "StylerMatch") — P4: the match funnel analytics for a tenant's stylist matches. The
 * headline is the <strong>accept-rate by rank</strong> — of the matches that converted to a booking,
 * which <em>rank</em> the client picked (rank 1 / rank 2 / rank 3+). A high rank-1 accept rate is the
 * proof the ranking is good (clients overwhelmingly book the stylist the engine put first). The
 * stylist-side twin of the T9 {@code StyleConsultAnalytics}.
 *
 * @param totalMatches      all matches submitted
 * @param matchesBooked     matches that converted to a booking
 * @param bookingRate       {@code matchesBooked / totalMatches} in [0,1] (0 if none)
 * @param top1BookedCount   booked matches where the client picked the rank-1 stylist
 * @param top2BookedCount   booked matches where the client picked the rank-2 stylist
 * @param top3PlusBookedCount booked matches where the client picked a rank-3-or-lower stylist
 * @param top1AcceptRate    {@code top1BookedCount / matchesBooked} in [0,1] (the ranking-quality signal)
 * @param avgTopScore       the mean rank-1 composite score across all matches (the match-confidence lever)
 */
public record StylerMatchAnalytics(
        long totalMatches,
        long matchesBooked,
        double bookingRate,
        long top1BookedCount,
        long top2BookedCount,
        long top3PlusBookedCount,
        double top1AcceptRate,
        double avgTopScore) {
}
