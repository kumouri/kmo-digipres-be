package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * T6 Salon "ReviewBoost" — the salon review dashboard read surface. ADMIN-gated; returns the per-stylist
 * review-insights board ({@link SalonReviewBoardDTO}) and a ReviewBoost wiring read-back
 * ({@link ReviewBoostConfigDTO}). The salon deployment of the E3 review engine's insights.
 *
 * <h2>Gating (the ChairFill flagship's two-key wall — the T3/T4/CF compose pattern)</h2>
 * <ul>
 *   <li><strong>Server-level</strong>: {@code @ConditionalOnProperty(kmosf.modules.chairfill, enabled)} —
 *       the salon flagship's gate (default-OFF; this controller is not even registered on a server without
 *       chairfill, so a disabled module → 404, the {@code WaitlistBoardController} precedent). The
 *       ReviewBoost beans additionally require {@code @ConditionalOnBean(SalonBookingService)} in
 *       {@link ReviewBoostAutoConfiguration}, so salon-spa must also be loaded.</li>
 *   <li><strong>Per-tenant</strong>: {@link TenantModuleRegistry#requireEnabled} for
 *       {@code chairfill} AND {@code salon-spa} (both are registered {@link
 *       com.kumouri.kmodigipresbe.extension.ModuleDefinition}s) — a tenant lacking either in
 *       {@code Tenant.enabledModules} → 1130/1132. (The gbp-reviews dependency is satisfied structurally:
 *       the reused {@code ReviewInsightsService} / request repo are always-present {@code @Service}s, not
 *       a registered {@code ModuleDefinition} — calling {@code requireEnabled("gbp-reviews")} would 1130,
 *       so it is intentionally NOT called; this matches the E3 {@code ReviewInsightsController} posture,
 *       deviation E3-D1.)</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 *
 * <p>Base path {@code /api/v1} (via {@code spring.webflux.base-path}), so these map to
 * {@code GET /api/v1/chairfill/reviewboost/...}.
 *
 * <p><strong>Error band 4410-4419</strong> (the {@code GlobalErrorHandler} Javadoc). T6's read surface
 * leans on the reused gate/role codes (1130/1132 module, 1800 ADMIN); the band is RESERVED for ReviewBoost
 * growth (no dedicated code is minted — recorded in {@code docs/PHASE-PROGRESS.md}).
 */
@RestController
@RequestMapping("/chairfill/reviewboost")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@RequiredArgsConstructor
public class ReviewBoostController {

    private final SalonReviewInsightsService service;
    private final TenantModuleRegistry modules;

    /**
     * The per-stylist review-insights board — the tenant-wide review-content + funnel header plus one
     * stylist funnel row per active staff member. ADMIN-gated + both-module-gated.
     */
    @GetMapping("/insights")
    public Mono<SalonReviewBoardDTO> insights() {
        return guard().then(service.board());
    }

    /**
     * The ReviewBoost wiring read-back (review link configured? + the effective default-OFF sender /
     * sentiment-refine / negative-alert flags). ADMIN-gated + both-module-gated.
     */
    @GetMapping("/config")
    public Mono<ReviewBoostConfigDTO> config() {
        return guard().then(service.config());
    }

    /** ADMIN + per-tenant chairfill + salon-spa membership wall, run before any read. */
    private Mono<Void> guard() {
        return RoleGuard.requireRole("ADMIN")
                .then(modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY))
                .then(modules.requireEnabled(SalonSpaAutoConfiguration.MODULE_KEY));
    }
}
