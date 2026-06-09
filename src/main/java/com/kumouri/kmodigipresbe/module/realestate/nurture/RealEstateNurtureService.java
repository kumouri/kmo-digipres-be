package com.kumouri.kmodigipresbe.module.realestate.nurture;

import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T1 (RE Database Goldmine) — the thin RE-namespaced facade over the shared E1 nurture services. It owns
 * NO new business logic: it delegates segment-and-enroll to the UNCHANGED {@link NurtureSegmentationService}
 * and the per-segment ROI read to the UNCHANGED {@link NurtureAnalyticsService} (T1 directive #7 — expose
 * an RE-scoped analytics read for the FE dashboard built in the next, FE leg). Existing as a distinct bean
 * lets the RE controller depend on an RE-module type (present only when realestate + nurture are both on)
 * rather than reaching across into the nurture module's controller.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateNurtureAutoConfiguration}. Runs under the
 * caller's tenant context (the RE controller resolves + ADMIN-guards it).
 */
public class RealEstateNurtureService {

    private final NurtureSegmentationService segmentation;
    private final NurtureAnalyticsService analytics;

    public RealEstateNurtureService(NurtureSegmentationService segmentation,
                                    NurtureAnalyticsService analytics) {
        this.segmentation = segmentation;
        this.analytics = analytics;
    }

    /**
     * Segment the tenant's contacts against the RE nurture campaign + enroll fresh matches. Delegates to
     * the shared engine verbatim (the A/B/C/D thresholds live on the campaign — no RE hardcoding).
     */
    public Mono<NurtureSegmentationService.SegmentationResult> segmentAndEnroll(UUID tenantId,
                                                                               UUID campaignId) {
        return segmentation.segmentAndEnroll(tenantId, campaignId);
    }

    /** Per-campaign + per-segment funnel ROI for the RE dashboard (delegates to the shared analytics). */
    public Mono<NurtureAnalyticsService.NurtureCampaignAnalytics> analytics(UUID tenantId, UUID campaignId) {
        return analytics.summarize(tenantId, campaignId);
    }
}
