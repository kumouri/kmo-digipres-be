package com.kumouri.kmodigipresbe.module.frontdesk.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.nurture.FrontDeskNurtureService;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
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
 * T2 (Health "RevenueRevive") — the frontdesk-namespaced admin surface for the dormant-patient nurture
 * deployment (the health twin of T1's {@code RealEstateNurtureController}). A thin frontdesk read/trigger
 * layer over the shared E1 nurture engine (campaign CRUD itself stays on the base {@code /nurture/campaigns}
 * controller); this exposes the two health-leg operations the reactivation dashboard FE needs: trigger
 * segment-and-enroll, and read the per-segment reactivation funnel.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code POST /frontdesk/nurture/campaigns/{id}/segment-and-enroll} — segment the tenant's contacts
 *       against the health nurture campaign + enroll fresh matches on <strong>logistics only</strong>
 *       (recency / value — the engine reads no clinical field); delegates to the unchanged
 *       {@code NurtureSegmentationService}; {@code @IdempotentRoute} (returns the per-run counts body).</li>
 *   <li>{@code GET /frontdesk/nurture/campaigns/{id}/analytics} — the per-campaign + per-segment funnel
 *       (delegates to the unchanged {@code NurtureAnalyticsService}).</li>
 * </ul>
 *
 * <h2>Gating (the {@code RealEstateNurtureController} precedent — BOTH modules)</h2>
 * <ul>
 *   <li>{@link ConditionalOnProperty}-gated on {@code kmosf.modules.frontdesk.enabled} so the controller is
 *       absent from the generated OpenAPI spec when frontdesk is off (the {@code AppointmentController}
 *       precedent — {@code OpenApiEndpointIT} runs without the flag);</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} for <strong>both</strong>
 *       {@code frontdesk} AND {@code nurture} (composed — the health-nurture deployment needs both modules
 *       enabled for the tenant; 1130/1132 otherwise);</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise) — campaign admin is an owner
 *       action, matching {@code NurtureCampaignController}.</li>
 * </ul>
 *
 * <p>Purely additive: the bean ({@link FrontDeskNurtureService}) exists only when frontdesk + nurture are
 * both on (its auto-config double-gates), so this controller's dependency resolves only then. Campaign
 * not-found / inactive / invalid reuse the shared nurture codes ({@code 4301}/{@code 4302}/{@code 4303}).
 */
@RestController
@RequestMapping("/frontdesk/nurture/campaigns")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
public class FrontDeskNurtureController {

    private final FrontDeskNurtureService service;
    private final TenantModuleRegistry modules;

    public FrontDeskNurtureController(FrontDeskNurtureService service, TenantModuleRegistry modules) {
        this.service = service;
        this.modules = modules;
    }

    /** Trigger segmentation + enroll for the health nurture campaign; returns the per-run counts. */
    @PostMapping("/{id}/segment-and-enroll")
    @IdempotentRoute
    public Mono<NurtureSegmentationService.SegmentationResult> segmentAndEnroll(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> service.segmentAndEnroll(ctx.tenantId(), id));
    }

    /** Per-campaign + per-segment reactivation funnel for the health dashboard. */
    @GetMapping("/{id}/analytics")
    public Mono<NurtureAnalyticsService.NurtureCampaignAnalytics> analytics(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> service.analytics(ctx.tenantId(), id));
    }

    /** Both modules loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(NurtureAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("ADMIN"));
    }
}
