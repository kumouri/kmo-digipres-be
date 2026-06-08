package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.NoShowScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * ChairFill — the personal-care / salon flagship module. Loaded only when
 * {@code kmosf.modules.chairfill.enabled=true} (CF-1 D3).
 *
 * <p>ChairFill rides the shipped {@link SalonSpaAutoConfiguration salon-spa} module — it reads
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}s and
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu}s — so a tenant must have BOTH
 * {@code salon-spa} and {@code chairfill} in {@code Tenant.enabledModules}. The beans here are
 * {@link ConditionalOnBean}({@link SalonBookingService}.class): if salon-spa is off on this server,
 * ChairFill silently no-ops (no bean is created), exactly like
 * {@link com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration}'s field-service
 * gate. <strong>Blast radius zero:</strong> with the property absent/false no ChairFill bean exists,
 * and the nightly job + any seeders skip non-{@code chairfill} tenants — Mole / Home-Services /
 * lead-scoring tenants and salon tenants without ChairFill are byte-identically unaffected.
 *
 * <p>Per-PR build-out:
 * <ul>
 *   <li><strong>CF-1 (this PR):</strong> the module skeleton + the no-show risk model
 *       ({@link NoShowRiskScoringService}, the nightly Smile {@code LogisticRegression} fork of
 *       {@code LeadScoringV2Service}), stamping {@code Booking.noShowRisk} on upcoming bookings and
 *       emitting {@code BOOKING_RISK_SCORED}. No outbound comms.</li>
 *   <li>CF-2: risk-tiered prevention (deposit-require for HIGH; Claude-personalized reminder).</li>
 *   <li>CF-3: gap-fill waitlist auto-offer (the double-YES-correct showpiece).</li>
 *   <li>CF-4: AI review-reply, salon-generalized.</li>
 *   <li>CF-5: FE surfaces (separate repo).</li>
 * </ul>
 */
@AutoConfiguration(after = SalonSpaAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class ChairFillAutoConfiguration {

    public static final String MODULE_KEY = "chairfill";

    @Bean
    public ModuleDefinition chairFillModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "ChairFill", "0.1.0",
                List.of("NO_SHOW_RISK"));
    }

    /**
     * The nightly no-show-risk scorer. {@link ConditionalOnBean}({@link SalonBookingService}.class)
     * so ChairFill no-ops when the salon-spa module it depends on is not loaded on this server.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public NoShowRiskScoringService noShowRiskScoringService(
            TenantRepository tenantRepository,
            BookingRepository bookingRepository,
            ServiceMenuRepository serviceMenuRepository,
            NoShowScoringJobRepository jobRepository,
            DomainEventPublisher eventPublisher,
            @Value("${kmosf.chairfill.noshow-scoring.high-threshold:" + NoShowRisk.DEFAULT_HIGH_THRESHOLD + "}")
            double highThreshold,
            @Value("${kmosf.chairfill.noshow-scoring.medium-threshold:" + NoShowRisk.DEFAULT_MEDIUM_THRESHOLD + "}")
            double mediumThreshold) {
        return new NoShowRiskScoringService(tenantRepository, bookingRepository, serviceMenuRepository,
                jobRepository, eventPublisher, highThreshold, mediumThreshold);
    }
}
