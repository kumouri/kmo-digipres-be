package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.gbp.ReviewInsightsService;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T6 Salon "ReviewBoost" — the salon deployment of the E3 review engine's per-entity insights, surfaced
 * as a per-stylist review board for the salon dashboard. Wires the one genuinely net-new bean
 * ({@link SalonReviewInsightsService}); everything else ReviewBoost needs already ships (the E3 engine +
 * the ChairFill flagship CF-1..CF-5).
 *
 * <h2>Both-modules gate (chairfill AND salon-spa — the ChairFill posture)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.chairfill.enabled)} on the class — the salon
 *       flagship's gate (default-OFF, the {@link ChairFillAutoConfiguration} posture);</li>
 *   <li>{@code @ConditionalOnBean(SalonBookingService.class)} — salon-spa must be loaded (its beans,
 *       incl. {@link SalonBookingService} + the {@link StaffMemberRepository} this reads, exist only when
 *       {@code kmosf.modules.salon-spa.enabled} is on). A chairfill deployment without salon-spa has no
 *       {@code SalonBookingService} → this whole config (and thus the bean the
 *       {@link ReviewBoostController} injects) is absent, exactly like the rest of ChairFill.
 *       {@code @AutoConfiguration(after=...)} guarantees both prerequisite configs are processed first.</li>
 * </ul>
 * Per-tenant membership ({@code Tenant.enabledModules} must carry BOTH {@code chairfill} and
 * {@code salon-spa}) is enforced by {@code TenantModuleRegistry.requireEnabled} in
 * {@link ReviewBoostController}.
 *
 * <h2>Reuse, not fork — every E3 / salon core stays empty-diff vs {@code main}</h2>
 * {@link SalonReviewInsightsService} assembles the board purely by calling the unchanged
 * {@link ReviewInsightsService} (tenant rollup + per-{@code STAFF}-subject rollup) over the active
 * stylists from {@link StaffMemberRepository}. No seam is added to any shipped service.
 *
 * <p><strong>Error band 4410-4419</strong> (the {@code GlobalErrorHandler} Javadoc) — RESERVED for
 * ReviewBoost growth; T6's read surface mints no dedicated code (it leans on 1130/1132 module + 1800
 * ADMIN). The default-OFF E3 flags ({@code sender-enabled}, {@code ai-refine-enabled},
 * {@code negative-alert-enabled}) are read into the {@link ReviewBoostConfigDTO} read-back only — T6
 * sends nothing; the actual sends remain E3's default-OFF jobs/services.
 */
@AutoConfiguration(after = {ChairFillAutoConfiguration.class, SalonSpaAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@ConditionalOnBean(SalonBookingService.class)
public class ReviewBoostAutoConfiguration {

    /**
     * The per-stylist review-insights aggregator + the ReviewBoost wiring read-back. Hand-constructed so
     * the {@code @Value}-resolved default-OFF flags land on the factory params (the salon-spa / ChairFill
     * {@code @Bean} construction pattern — a component-scanned {@code @Value} would not fire). The flag
     * keys mirror the E3 services' own keys verbatim so the read-back is truthful: the sender's
     * {@code kmosf.modules.review-engine.sender-enabled}, the sentiment service's
     * {@code kmosf.review-engine.ai-refine-enabled}, and the alert service's
     * {@code kmosf.review-engine.negative-alert-enabled} (all default-OFF).
     */
    @Bean
    public SalonReviewInsightsService salonReviewInsightsService(
            ReviewInsightsService reviewInsightsService,
            StaffMemberRepository staffMembers,
            IntegrationConnectionRepository connections,
            @Value("${kmosf.modules.review-engine.sender-enabled:false}") boolean senderEnabled,
            @Value("${kmosf.review-engine.ai-refine-enabled:false}") boolean sentimentRefineEnabled,
            @Value("${kmosf.review-engine.negative-alert-enabled:false}") boolean negativeAlertEnabled) {
        return new SalonReviewInsightsService(reviewInsightsService, staffMembers, connections,
                senderEnabled, sentimentRefineEnabled, negativeAlertEnabled);
    }
}
