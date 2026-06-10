package com.kumouri.kmodigipresbe.module.quoting.closer;

import java.math.BigDecimal;

/**
 * T11 (Home "QuoteCloser") — the abandonment + recovery funnel for a tenant. The read-endpoint payload.
 *
 * @param quotesSent      total {@code QuoteRequest}s submitted for the tenant (the funnel top)
 * @param followedUp      quotes whose contact was enrolled in the QuoteCloser nurture campaign (a nudge
 *                        cadence was started for the un-accepted quote)
 * @param recovered       followed-up quotes that were then ACCEPTED / BOOKED (accepted-after-nudge — the
 *                        recovery)
 * @param reviewRequested won quotes for which a post-job review request was created (the E3 review leg)
 * @param recoveryRate    {@code recovered / followedUp} (scale-2 HALF_UP; 0 when nothing was followed up)
 */
public record QuoteCloserAnalytics(
        long quotesSent,
        long followedUp,
        long recovered,
        long reviewRequested,
        BigDecimal recoveryRate) {
}
