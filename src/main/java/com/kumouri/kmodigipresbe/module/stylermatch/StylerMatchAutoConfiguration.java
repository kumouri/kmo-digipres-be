package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.module.stylermatch.repository.StylerMatchRepository;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchAnalyticsService;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchBookingService;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchScoringService;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T12 (Salon "StylerMatch") — the {@code stylermatch} vertical module: match a new client's requested
 * service/style to the best-fit stylist (specialty fit + availability + preference) via a
 * <strong>pure, deterministic, explainable</strong> scorer (NOT an LLM call), then book via the unchanged
 * salon booking path. The stylist-side twin of T9 StyleConsult (which ranks services + retail).
 *
 * <h2>Rides the ChairFill salon flagship (the T9 StyleConsult posture)</h2>
 * StylerMatch is a salon-flagship capability, so it gates on the existing {@code chairfill} module key
 * rather than minting a new one (a tenant already on the salon flagship gets it; no new
 * {@code ModuleDefinition}). Two-part gate:
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.chairfill.enabled)} on the class — default-OFF (the
 *       {@link ChairFillAutoConfiguration} posture); absent the flag the whole module (beans, controllers,
 *       the public widget) is not even created &rarr; off by default + out of the OpenAPI spec;</li>
 *   <li>{@code @ConditionalOnBean(SalonBookingService.class)} — salon-spa must be loaded (StylerMatch
 *       reads its {@link StaffMemberRepository}/{@link ServiceMenuRepository}/{@link BookingRepository}
 *       and books through {@link SalonBookingService}). A chairfill deployment without salon-spa has no
 *       {@code SalonBookingService} &rarr; this whole config is absent, exactly like StyleConsult.
 *       {@code @AutoConfiguration(after=...)} guarantees both prerequisite configs are processed first.</li>
 * </ul>
 * Per-tenant membership ({@code Tenant.enabledModules} carrying {@code chairfill} + {@code salon-spa}) is
 * enforced by {@code TenantModuleRegistry.requireEnabled} in the staff/admin controllers; the public
 * intake/accept controllers carry the same {@code @ConditionalOnProperty} (token-only auth, the T9
 * precedent: disabled &rarr; bean absent &rarr; endpoint not registered &rarr; 404).
 *
 * <h2>Beans (hand-constructed so none exist when the module is off; controllers are component-scanned)</h2>
 * <ul>
 *   <li>{@link StylerMatchScoringService} — the pure deterministic stylist scorer (P1).</li>
 *   <li>{@link StylerMatchService} — the match orchestrator (token/staff &rarr; load &rarr; score &rarr;
 *       persist + lead, P2).</li>
 *   <li>{@link StylerMatchBookingService} — the match&rarr;booking funnel via the unchanged
 *       {@link SalonBookingService#create} (P3).</li>
 *   <li>{@link StylerMatchAnalyticsService} — the accept-rate-by-rank funnel (P4).</li>
 * </ul>
 *
 * <p>Reuses the shipped cores empty-diff: {@link SalonBookingService}, {@link StaffMemberRepository},
 * {@link ServiceMenuRepository}, {@link BookingRepository}, {@link PublicWidgetTokenService},
 * {@link ContactRepository}, {@link TwilioSmsService}, {@link IntegrationConnectionRepository}. The only
 * reused-model edit is the additive {@code StaffMember.specialties} field.
 *
 * <p><strong>Error band 4480-4489</strong> (the {@code GlobalErrorHandler} Javadoc table).
 */
@AutoConfiguration(after = {ChairFillAutoConfiguration.class, SalonSpaAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@ConditionalOnBean(SalonBookingService.class)
public class StylerMatchAutoConfiguration {

    @Bean
    public StylerMatchScoringService stylerMatchScoringService(
            @Value("${kmosf.stylermatch.default-zone:UTC}") String defaultZone) {
        return new StylerMatchScoringService(defaultZone);
    }

    @Bean
    public StylerMatchService stylerMatchService(
            PublicWidgetTokenService tokens,
            StylerMatchRepository matches,
            StaffMemberRepository staff,
            ServiceMenuRepository menus,
            BookingRepository bookings,
            ContactRepository contacts,
            StylerMatchScoringService stylerMatchScoringService,
            DomainEventPublisher events) {
        return new StylerMatchService(tokens, matches, staff, menus, bookings, contacts,
                stylerMatchScoringService, events);
    }

    @Bean
    public StylerMatchBookingService stylerMatchBookingService(
            StylerMatchRepository matches,
            SalonBookingService salonBookingService,
            ServiceMenuRepository menus,
            IntegrationConnectionRepository connections,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events) {
        return new StylerMatchBookingService(matches, salonBookingService, menus, connections,
                twilioSmsService, events);
    }

    @Bean
    public StylerMatchAnalyticsService stylerMatchAnalyticsService(StylerMatchRepository matches) {
        return new StylerMatchAnalyticsService(matches);
    }
}
