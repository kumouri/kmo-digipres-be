package com.kumouri.kmodigipresbe.module.stylermatch.service;

import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchAnalytics;
import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;
import com.kumouri.kmodigipresbe.module.stylermatch.repository.StylerMatchRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — P4: the <strong>match-funnel</strong> analytics read. Aggregates a
 * tenant's {@link StylerMatch}es into the {@link StylerMatchAnalytics} funnel — total matches, matches
 * booked, the booking rate, the <strong>accept-rate by rank</strong> (which rank the client booked), and
 * the average rank-1 score. A pure DB scan over the tenant's matches (explicit-tenant
 * {@code findByTenantId}); no mutation. The T9 {@code StyleConsultAnalyticsService} shape.
 */
@RequiredArgsConstructor
public class StylerMatchAnalyticsService {

    private final StylerMatchRepository matches;

    /** Compute the match funnel over all of {@code tenantId}'s styler matches. */
    public Mono<StylerMatchAnalytics> analytics(UUID tenantId) {
        return matches.findByTenantId(tenantId).collectList()
                .map(StylerMatchAnalyticsService::aggregate);
    }

    static StylerMatchAnalytics aggregate(List<StylerMatch> all) {
        long total = all.size();
        long booked = 0;
        long top1 = 0;
        long top2 = 0;
        long top3Plus = 0;
        BigDecimal topScoreSum = BigDecimal.ZERO;
        long topScoreCount = 0;

        for (StylerMatch m : all) {
            boolean isBooked = m.getStatus() == StylerMatchStatus.BOOKED;
            if (isBooked) {
                booked++;
                Integer rank = m.getSelectedRank();
                if (rank != null) {
                    if (rank == 1) {
                        top1++;
                    } else if (rank == 2) {
                        top2++;
                    } else {
                        top3Plus++;
                    }
                }
            }
            List<RankedMatch> ranked = m.getRankedMatches();
            if (ranked != null && !ranked.isEmpty()) {
                topScoreSum = topScoreSum.add(BigDecimal.valueOf(ranked.get(0).getScore()));
                topScoreCount++;
            }
        }

        double bookingRate = total == 0 ? 0.0 : (double) booked / total;
        double top1AcceptRate = booked == 0 ? 0.0 : (double) top1 / booked;
        double avgTopScore = topScoreCount == 0 ? 0.0
                : topScoreSum.divide(BigDecimal.valueOf(topScoreCount), 4, RoundingMode.HALF_UP)
                        .doubleValue();

        return new StylerMatchAnalytics(total, booked, round(bookingRate), top1, top2, top3Plus,
                round(top1AcceptRate), avgTopScore);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
