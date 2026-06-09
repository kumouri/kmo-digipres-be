package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureSendLogRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-campaign + per-segment funnel analytics for the Nurture / Cadence Engine (E1). Pure reads over
 * the three nurture repos, every finder explicitly tenant-scoped.
 *
 * <p>The read endpoint backing the dashboard FE (which rides with the first consuming tool) calls
 * {@link #summarize}.
 */
@RequiredArgsConstructor
public class NurtureAnalyticsService {

    private final NurtureCampaignRepository campaigns;
    private final NurtureEnrollmentRepository enrollments;
    private final NurtureSendLogRepository sendLogs;

    /**
     * Summarize one campaign's funnel.
     *
     * @param tenantId   the tenant
     * @param campaignId the campaign
     * @return per-campaign + per-bucket enrolled/active/replied/booked/optedOut/completed/exited counts
     *         + the total sent (ledger rows)
     */
    public Mono<NurtureCampaignAnalytics> summarize(UUID tenantId, UUID campaignId) {
        return campaigns.findByTenantIdAndId(tenantId, campaignId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Nurture campaign not found", 4301, 404)))
                .flatMap(campaign -> enrollments
                        .findAllByTenantIdAndCampaignId(tenantId, campaignId)
                        .collectList()
                        .flatMap(rows -> countSent(tenantId, rows)
                                .map(sent -> build(campaign.getId(), campaign.getName(), rows, sent))));
    }

    /** Count send-log rows across the campaign's enrollments (the sent total). */
    private Mono<Long> countSent(UUID tenantId, java.util.List<NurtureEnrollment> rows) {
        return reactor.core.publisher.Flux.fromIterable(rows)
                .flatMap(enr -> sendLogs.findAllByTenantIdAndEnrollmentId(tenantId, enr.getId()))
                .count();
    }

    private static NurtureCampaignAnalytics build(UUID campaignId, String name,
                                                  java.util.List<NurtureEnrollment> rows, long sent) {
        Counts overall = new Counts();
        Map<DormancyBucket, Counts> byBucket = new EnumMap<>(DormancyBucket.class);
        for (NurtureEnrollment e : rows) {
            overall.tally(e.getStatus());
            DormancyBucket bucket = e.getBucket();
            if (bucket != null) {
                byBucket.computeIfAbsent(bucket, b -> new Counts()).tally(e.getStatus());
            }
        }
        Map<DormancyBucket, NurtureSegmentCounts> bucketView = new EnumMap<>(DormancyBucket.class);
        byBucket.forEach((b, c) -> bucketView.put(b, c.toView()));
        return new NurtureCampaignAnalytics(
                campaignId, name,
                overall.total(), overall.enrolled, overall.active, overall.replied,
                overall.booked, overall.optedOut, overall.completed, overall.exited,
                sent, bucketView);
    }

    /** Mutable accumulator. */
    private static final class Counts {
        int enrolled, active, replied, booked, optedOut, completed, exited;

        void tally(NurtureEnrollmentStatus s) {
            if (s == null) {
                return;
            }
            switch (s) {
                case ENROLLED -> enrolled++;
                case ACTIVE -> active++;
                case REPLIED -> replied++;
                case BOOKED -> booked++;
                case OPTED_OUT -> optedOut++;
                case COMPLETED -> completed++;
                case EXITED -> exited++;
            }
        }

        int total() {
            return enrolled + active + replied + booked + optedOut + completed + exited;
        }

        NurtureSegmentCounts toView() {
            return new NurtureSegmentCounts(total(), enrolled, active, replied, booked, optedOut,
                    completed, exited);
        }
    }

    /**
     * Per-campaign funnel.
     *
     * @param campaignId the campaign
     * @param name       the campaign name
     * @param total      total enrollments
     * @param enrolled   ENROLLED count
     * @param active     ACTIVE count
     * @param replied    REPLIED count
     * @param booked     BOOKED count
     * @param optedOut   OPTED_OUT count
     * @param completed  COMPLETED count
     * @param exited     EXITED count
     * @param sent       total cadence touches sent (ledger rows across the campaign's enrollments)
     * @param perBucket  the same breakdown per {@link DormancyBucket}
     */
    public record NurtureCampaignAnalytics(
            UUID campaignId,
            String name,
            int total,
            int enrolled,
            int active,
            int replied,
            int booked,
            int optedOut,
            int completed,
            int exited,
            long sent,
            Map<DormancyBucket, NurtureSegmentCounts> perBucket) {
    }

    /**
     * Per-segment (bucket) counts.
     *
     * @param total     enrollments in the bucket
     * @param enrolled  ENROLLED
     * @param active    ACTIVE
     * @param replied   REPLIED
     * @param booked    BOOKED
     * @param optedOut  OPTED_OUT
     * @param completed COMPLETED
     * @param exited    EXITED
     */
    public record NurtureSegmentCounts(
            int total,
            int enrolled,
            int active,
            int replied,
            int booked,
            int optedOut,
            int completed,
            int exited) {
    }
}
