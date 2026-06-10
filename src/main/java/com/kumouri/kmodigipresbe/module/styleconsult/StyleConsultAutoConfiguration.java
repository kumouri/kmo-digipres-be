package com.kumouri.kmodigipresbe.module.styleconsult;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.module.styleconsult.repository.StyleConsultRepository;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultAnalyticsService;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultBookingService;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultService;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultVisionService;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleRecommendationService;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T9 (Salon "StyleConsult AI") — the {@code styleconsult} vertical module: a salon-prospect-facing
 * instant style consult. The salon's first <strong>vision-COMPOSITION</strong> use — an inspiration
 * photo becomes service + <strong>margin-aware retail</strong> recommendations in seconds (reusing the
 * shipped vision spine {@code AiVisionService.extract}), then a booked appointment via the unchanged
 * salon booking path.
 *
 * <h2>Rides the ChairFill salon flagship (the T6 ReviewBoost posture)</h2>
 * StyleConsult is a salon-flagship capability, so it gates on the existing {@code chairfill} module key
 * rather than minting a new one (a tenant already on the salon flagship gets it; no new
 * {@code ModuleDefinition}). Two-part gate:
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.chairfill.enabled)} on the class — default-OFF (the
 *       {@link ChairFillAutoConfiguration} posture); absent the flag the whole module (beans,
 *       controllers, the public widget) is not even created → off by default + out of the OpenAPI spec;</li>
 *   <li>{@code @ConditionalOnBean(SalonBookingService.class)} — salon-spa must be loaded (StyleConsult
 *       reads its {@link ServiceMenuRepository} + books through {@link SalonBookingService}). A chairfill
 *       deployment without salon-spa has no {@code SalonBookingService} → this whole config is absent,
 *       exactly like ReviewBoost. {@code @AutoConfiguration(after=...)} guarantees both prerequisite
 *       configs are processed first.</li>
 * </ul>
 * Per-tenant membership ({@code Tenant.enabledModules} carrying {@code chairfill} + {@code salon-spa})
 * is enforced by {@code TenantModuleRegistry.requireEnabled} in the staff/admin controllers; the public
 * intake/accept controllers carry the same {@code @ConditionalOnProperty} (token-only auth, the
 * {@code ServiceRequestWidgetController}/{@code QuoteIntakeController} precedent: disabled → bean absent
 * → endpoint not registered → 404).
 *
 * <h2>Beans (hand-constructed so none exist when the module is off; controllers are component-scanned)</h2>
 * <ul>
 *   <li>{@link StyleConsultVisionService} — S1 inspo-photo → style attributes ({@code extract}-shaped,
 *       reusing the unchanged {@link AiVisionService}).</li>
 *   <li>{@link StyleRecommendationService} — S2 pure style→service rules + margin-aware retail ranking.</li>
 *   <li>{@link StyleConsultService} — S1 vision-COMPOSITION orchestrator (token → vision → recommend →
 *       persist + lead).</li>
 *   <li>{@link StyleConsultBookingService} — S3 consult→booking via the unchanged
 *       {@link SalonBookingService#create}.</li>
 *   <li>{@link StyleConsultAnalyticsService} — S4 retail-attach funnel.</li>
 * </ul>
 *
 * <p>Reuses the shipped cores empty-diff: {@link AiVisionService}, {@link FileStorageService},
 * {@link AttachmentRepository}, {@link ProductRepository}, {@link ServiceMenuRepository},
 * {@link SalonBookingService}, {@link PublicWidgetTokenService}, {@link ContactRepository},
 * {@link TwilioSmsService}, {@link IntegrationConnectionRepository}.
 *
 * <p><strong>Error band 4450-4459</strong> (the {@code GlobalErrorHandler} Javadoc table).
 */
@AutoConfiguration(after = {ChairFillAutoConfiguration.class, SalonSpaAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@ConditionalOnBean(SalonBookingService.class)
public class StyleConsultAutoConfiguration {

    @Bean
    public StyleConsultVisionService styleConsultVisionService(
            FileStorageService storage,
            AttachmentRepository attachments,
            AiVisionService visionService,
            @Value("${kmosf.styleconsult.vision-model:claude-sonnet-4-5}") String visionModel) {
        return new StyleConsultVisionService(storage, attachments, visionService, visionModel);
    }

    @Bean
    public StyleRecommendationService styleRecommendationService(
            ServiceMenuRepository menus,
            ProductRepository products,
            @Value("${kmosf.styleconsult.max-services:3}") int maxServices,
            @Value("${kmosf.styleconsult.max-products:3}") int maxProducts) {
        return new StyleRecommendationService(menus, products, maxServices, maxProducts);
    }

    @Bean
    public StyleConsultService styleConsultService(
            PublicWidgetTokenService tokens,
            StyleConsultRepository consults,
            ContactRepository contacts,
            StyleConsultVisionService styleConsultVisionService,
            StyleRecommendationService styleRecommendationService,
            DomainEventPublisher events) {
        return new StyleConsultService(tokens, consults, contacts, styleConsultVisionService,
                styleRecommendationService, events);
    }

    @Bean
    public StyleConsultBookingService styleConsultBookingService(
            StyleConsultRepository consults,
            SalonBookingService salonBookingService,
            IntegrationConnectionRepository connections,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events) {
        return new StyleConsultBookingService(consults, salonBookingService, connections,
                twilioSmsService, events);
    }

    @Bean
    public StyleConsultAnalyticsService styleConsultAnalyticsService(StyleConsultRepository consults) {
        return new StyleConsultAnalyticsService(consults);
    }
}
