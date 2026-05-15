package com.kumouri.kmodigipresbe.module.salonspa;

import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Salon/spa vertical module. Loaded only when
 * {@code kmosf.modules.salon-spa.enabled=true}.
 *
 * <p>Adds {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu},
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}, and
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyAccount} — Phase 12
 * introduces the salon/spa vertical (hair salons, spas, nail studios, fitness studios)
 * alongside the marketing infrastructure (forms, landing pages, UTM capture).
 *
 * <p>This module has no hard dependency on the field-service or home-services modules;
 * it stands alone on the Phase 7 (products/payments) and Phase 9 (sequences/widgets)
 * platform primitives.
 *
 * <p>Per-sub-PR build-out within Phase 12:
 * <ul>
 *   <li><strong>12a (this PR):</strong> module skeleton — entities, repositories,
 *       and this {@code AutoConfiguration} registering the {@link ModuleDefinition}.
 *       No service or controller beans yet.</li>
 *   <li>12b: {@code BookingPolicyService}, {@code SalonBookingService}, booking
 *       controllers, and the public booking widget.</li>
 *   <li>12c: {@code LoyaltyAccrualService}, {@code RebookingNudgeService}, loyalty
 *       controller.</li>
 *   <li>12d: Square POS integration ({@code SquareAutoConfiguration}).</li>
 *   <li>12e: Marketing infrastructure — {@code FormDefinition}, {@code LandingPage},
 *       UTM capture, {@code FirstTouch} on Contact (core, not module-gated).</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.salon-spa", name = "enabled")
public class SalonSpaAutoConfiguration {

    public static final String MODULE_KEY = "salon-spa";

    @Bean
    public ModuleDefinition salonSpaModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Salon & Spa", "0.1.0",
                List.of("SERVICE_MENU", "BOOKING", "LOYALTY_ACCOUNT"));
    }
}
