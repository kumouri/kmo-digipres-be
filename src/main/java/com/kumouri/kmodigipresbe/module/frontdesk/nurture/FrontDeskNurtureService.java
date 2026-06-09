package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T2 (Health "RevenueRevive") — the thin frontdesk-namespaced facade over the shared E1 nurture services
 * (the health twin of T1's {@code RealEstateNurtureService}). It owns NO new business logic: it delegates
 * segment-and-enroll to the UNCHANGED {@link NurtureSegmentationService} and the per-segment funnel read to
 * the UNCHANGED {@link NurtureAnalyticsService} (the FE reactivation dashboard built in the next, FE leg).
 * Existing as a distinct bean lets the frontdesk controller depend on a frontdesk-module type (present only
 * when frontdesk + nurture are both on) rather than reaching across into the nurture module's controller.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code FrontDeskNurtureAutoConfiguration}. Runs under the
 * caller's tenant context (the frontdesk controller resolves + ADMIN-guards it).
 */
public class FrontDeskNurtureService {

    private final NurtureSegmentationService segmentation;
    private final NurtureAnalyticsService analytics;

    public FrontDeskNurtureService(NurtureSegmentationService segmentation,
                                   NurtureAnalyticsService analytics) {
        this.segmentation = segmentation;
        this.analytics = analytics;
    }

    /**
     * Segment the tenant's contacts against the health nurture campaign + enroll fresh matches. Delegates to
     * the shared engine verbatim — the dormancy windows (and optional value band) live on the campaign's
     * segment definitions, so segmentation uses <strong>logistics only</strong> (recency / value), never a
     * clinical feature (there is no clinical field for the engine to read).
     */
    public Mono<NurtureSegmentationService.SegmentationResult> segmentAndEnroll(UUID tenantId,
                                                                               UUID campaignId) {
        return segmentation.segmentAndEnroll(tenantId, campaignId);
    }

    /** Per-campaign + per-segment reactivation funnel for the health dashboard (delegates to analytics). */
    public Mono<NurtureAnalyticsService.NurtureCampaignAnalytics> analytics(UUID tenantId, UUID campaignId) {
        return analytics.summarize(tenantId, campaignId);
    }
}
