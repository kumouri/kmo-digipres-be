package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — the abandonment + recovery analytics: quotes sent → followed-up (enrolled in
 * the QuoteCloser cadence) → recovered (accepted-after-nudge) → review-requested, with the recovery rate.
 * A pure read service over the reused quoting / nurture / review-request repos (every finder carries an
 * explicit {@code tenantId}); no engine edit.
 *
 * <h2>Funnel definitions</h2>
 * <ul>
 *   <li><strong>quotesSent</strong> — every {@code QuoteRequest} for the tenant.</li>
 *   <li><strong>followedUp</strong> — the count of contacts the tenant's QuoteCloser campaign enrolled (one
 *       enrollment per contact; the un-accepted-quote nudge cadence was started).</li>
 *   <li><strong>recovered</strong> — quotes now {@code ACCEPTED} or {@code BOOKED} whose contact was a
 *       QuoteCloser enrollee (accepted-after-nudge). Counted per recovered quote (a contact may have one).</li>
 *   <li><strong>reviewRequested</strong> — the E3 {@code ReviewRequest}s created from a {@code QUOTE_ACCEPTED}
 *       (subjectType {@code OTHER}, sourceEventType {@code quote.accepted}) — the won-job review leg.</li>
 *   <li><strong>recoveryRate</strong> — {@code recovered / followedUp} (scale-2; 0 when nothing followed up).</li>
 * </ul>
 * The campaign id is the tenant's {@link QuoteCloserConfig#getCampaignId()}; with no config / no campaign the
 * follow-up + recovery legs are 0 (only quotesSent + reviewRequested are populated).
 */
@RequiredArgsConstructor
public class QuoteCloserAnalyticsService {

    private final QuoteCloserConfigRepository configs;
    private final QuoteRequestRepository quotes;
    private final NurtureEnrollmentRepository enrollments;
    private final ReviewRequestRepository reviewRequests;

    public Mono<QuoteCloserAnalytics> analytics(UUID tenantId) {
        Mono<Long> quotesSent = quotes.findByTenantIdOrderByCreatedAtDesc(tenantId).count();
        Mono<Long> reviewRequested = reviewRequests.findByTenantId(tenantId)
                .filter(r -> r.getSubjectType() == ReviewSubjectType.OTHER
                        && "quote.accepted".equals(r.getSourceEventType()))
                .count();

        // The QuoteCloser-enrolled contact set + the won-after-nudge count both need the campaign id.
        Mono<long[]> followedAndRecovered = configs.findByTenantId(tenantId)
                .map(QuoteCloserConfig::getCampaignId)
                .flatMap(campaignId -> campaignId == null
                        ? Mono.just(new long[]{0L, 0L})
                        : followedAndRecovered(tenantId, campaignId))
                .defaultIfEmpty(new long[]{0L, 0L});

        return Mono.zip(quotesSent, reviewRequested, followedAndRecovered)
                .map(t -> {
                    long sent = t.getT1();
                    long reviews = t.getT2();
                    long followedUp = t.getT3()[0];
                    long recovered = t.getT3()[1];
                    return new QuoteCloserAnalytics(sent, followedUp, recovered, reviews,
                            rate(recovered, followedUp));
                });
    }

    /**
     * Returns {@code [followedUp, recovered]}: the count of enrolled contacts, and the count of ACCEPTED /
     * BOOKED quotes whose contact is one of those enrollees.
     */
    private Mono<long[]> followedAndRecovered(UUID tenantId, UUID campaignId) {
        return enrollments.findAllByTenantIdAndCampaignId(tenantId, campaignId)
                .map(NurtureEnrollment::getContactId)
                .filter(c -> c != null)
                .collect(HashSet<UUID>::new, Set::add)
                .flatMap(enrolledContacts -> recoveredCount(tenantId, enrolledContacts)
                        .map(recovered -> new long[]{enrolledContacts.size(), recovered}));
    }

    /** Count of ACCEPTED / BOOKED quotes whose contact is in the enrolled set (accepted-after-nudge). */
    private Mono<Long> recoveredCount(UUID tenantId, Set<UUID> enrolledContacts) {
        if (enrolledContacts.isEmpty()) {
            return Mono.just(0L);
        }
        return quotes.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .filter(q -> q.getStatus() == QuoteStatus.ACCEPTED || q.getStatus() == QuoteStatus.BOOKED)
                .filter(q -> q.getContactId() != null && enrolledContacts.contains(q.getContactId()))
                .count();
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
