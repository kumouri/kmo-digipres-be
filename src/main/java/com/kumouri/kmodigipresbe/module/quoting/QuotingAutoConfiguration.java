package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.quoting.repository.PriceBookRepository;
import com.kumouri.kmodigipresbe.module.quoting.service.PriceBookService;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * T8 (Home Services "QuoteNow") — the {@code quoting} vertical module: a homeowner-facing instant
 * quote + repair-vs-replace advisor. The first <strong>vision-COMPOSITION</strong> flagship — photos
 * / a description become a defensible price RANGE in minutes (reusing the shipped vision spine
 * {@code AiVisionService.extract}), then a repair-vs-replace recommendation, then a booked visit.
 *
 * <h2>Single-module gate — DEFAULT OFF</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.quoting", name="enabled")} with <strong>no
 * {@code matchIfMissing}</strong> (the {@link com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration}
 * posture) — absent the flag the whole module (beans, controllers, the public widget) is not even
 * created, so it is off by default and out of the OpenAPI spec. Per-tenant membership is enforced by
 * {@code TenantModuleRegistry.requireEnabled("quoting")} in the staff/admin controllers; the public
 * intake controllers carry the same {@code @ConditionalOnProperty} (token-only auth, the
 * {@code ServiceRequestWidgetController}/{@code EquipmentPhotoController} precedent: disabled module →
 * bean absent → endpoint not registered → 404).
 *
 * <h2>Beans (hand-constructed so none exist when the module is off)</h2>
 * <ul>
 *   <li>{@link ModuleDefinition} — registers the {@code quoting} module with the
 *       {@code TenantModuleRegistry} (so {@code requireEnabled} resolves 1130 vs 1132 correctly).</li>
 *   <li>{@link QuoteSynthesisService} — Q1 pure range synthesis from the price book.</li>
 *   <li>{@link PriceBookService} — Q1 per-tenant price-book CRUD.</li>
 *   <li>(Q2/Q3) {@code QuoteVisionService}, {@code RepairVsReplaceReasoner},
 *       {@code QuoteIntakeService}, {@code QuoteBookingService} — added in their sub-phases.</li>
 * </ul>
 *
 * <p><strong>Error band 4430-4449</strong> (the {@code GlobalErrorHandler} Javadoc table).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuotingAutoConfiguration {

    public static final String MODULE_KEY = "quoting";

    @Bean
    public ModuleDefinition quotingModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "QuoteNow", "0.1.0",
                List.of("PRICE_BOOK", "QUOTE_REQUEST"));
    }

    @Bean
    public QuoteSynthesisService quoteSynthesisService() {
        return new QuoteSynthesisService();
    }

    @Bean
    public PriceBookService priceBookService(PriceBookRepository priceBooks) {
        return new PriceBookService(priceBooks);
    }
}
