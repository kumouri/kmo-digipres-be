package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * T11 (Home "QuoteCloser") — the office read surface for the abandonment + recovery funnel.
 *
 * <h2>Endpoint (under {@code spring.webflux.base-path=/api/v1})</h2>
 * {@code GET /quoting/quote-closer/analytics} → {@link QuoteCloserAnalytics} (quotes sent → followed-up →
 * recovered → review-requested + the recovery rate).
 *
 * <h2>Gating (BOTH modules — the T1/T3 both-module posture)</h2>
 * Class {@code @ConditionalOnProperty(kmosf.modules.quoting.enabled)} (absent from the OpenAPI spec + 404
 * when quoting is off — the {@code QuoteInboxController} precedent; default-OFF). Per-tenant membership via
 * {@link TenantModuleRegistry#requireEnabled} for <strong>both</strong> {@code quoting} AND {@code nurture}
 * (1130/1132). Staff-accessible (the authenticated chain — the {@code QuoteInboxController} precedent; no
 * extra RoleGuard).
 */
@RestController
@RequestMapping("/quoting/quote-closer")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuoteCloserController {

    private final QuoteCloserAnalyticsService analyticsService;
    private final TenantModuleRegistry modules;

    public QuoteCloserController(QuoteCloserAnalyticsService analyticsService,
                                TenantModuleRegistry modules) {
        this.analyticsService = analyticsService;
        this.modules = modules;
    }

    @GetMapping("/analytics")
    public Mono<QuoteCloserAnalytics> analytics() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> analyticsService.analytics(ctx.tenantId()));
    }

    /** quoting AND nurture loaded + enabled for the tenant — the both-module order. */
    private Mono<Void> guard() {
        return modules.requireEnabled(QuotingAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(NurtureAutoConfiguration.MODULE_KEY));
    }
}
