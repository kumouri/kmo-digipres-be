package com.kumouri.kmodigipresbe.module.realestate.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.nurture.RealEstateNurtureService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T1 (RE Database Goldmine) — the RE-namespaced admin surface for the dormant-lead nurture deployment.
 * A thin RE read/trigger layer over the shared E1 nurture engine (campaign CRUD itself stays on the base
 * {@code /nurture/campaigns} controller); this exposes the two RE-leg operations the dashboard FE needs:
 * trigger segment-and-enroll, and read the per-segment ROI funnel (T1 directive #7).
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code POST /realestate/nurture/campaigns/{id}/segment-and-enroll} — segment the tenant's
 *       contacts against the RE nurture campaign + enroll fresh matches (delegates to the unchanged
 *       {@code NurtureSegmentationService}); {@code @IdempotentRoute} (returns the per-run counts body).</li>
 *   <li>{@code GET /realestate/nurture/campaigns/{id}/analytics} — the per-campaign + per-segment funnel
 *       (delegates to the unchanged {@code NurtureAnalyticsService}).</li>
 * </ul>
 *
 * <h2>Gating (the {@code ConciergeConversationController} precedent — BOTH modules)</h2>
 * <ul>
 *   <li>{@link ConditionalOnProperty}-gated on {@code kmosf.modules.realestate.enabled} so the controller
 *       is absent from the generated OpenAPI spec when realestate is off (the {@code ListingController}
 *       precedent — {@code OpenApiEndpointIT} runs without the flag);</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} for <strong>both</strong>
 *       {@code realestate} AND {@code nurture} (composed — the RE-nurture deployment needs both modules
 *       enabled for the tenant; 1130/1132 otherwise);</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise) — campaign admin is an
 *       owner action, matching {@code NurtureCampaignController}.</li>
 * </ul>
 *
 * <p>Purely additive: the bean ({@link RealEstateNurtureService}) exists only when realestate + nurture
 * are both on (its auto-config double-gates), so this controller's dependency resolves only then. Campaign
 * not-found / inactive / invalid reuse the shared nurture codes ({@code 4301}/{@code 4302}/{@code 4303}).
 */
@RestController
@RequestMapping("/realestate/nurture/campaigns")
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
public class RealEstateNurtureController {

    private final RealEstateNurtureService service;
    private final TenantModuleRegistry modules;

    public RealEstateNurtureController(RealEstateNurtureService service, TenantModuleRegistry modules) {
        this.service = service;
        this.modules = modules;
    }

    /** Trigger segmentation + enroll for the RE nurture campaign; returns the per-run counts. */
    @PostMapping("/{id}/segment-and-enroll")
    @IdempotentRoute
    public Mono<NurtureSegmentationService.SegmentationResult> segmentAndEnroll(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> service.segmentAndEnroll(ctx.tenantId(), id));
    }

    /** Per-campaign + per-segment funnel ROI for the RE dashboard. */
    @GetMapping("/{id}/analytics")
    public Mono<NurtureAnalyticsService.NurtureCampaignAnalytics> analytics(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> service.analytics(ctx.tenantId(), id));
    }

    /** Both modules loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(NurtureAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("ADMIN"));
    }
}
