package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.concierge.ConciergeInboundRouter;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.responder.DefaultHandoffIntentHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T3 (Real Estate "Midnight Responder") — the both-module auto-configuration that wires the routing +
 * completeness layer over the shipped RE concierge. The {@code RealEstateNurtureAutoConfiguration}
 * (realestate + nurture) precedent, here for realestate + responder.
 *
 * <h2>Both-modules gate (realestate AND responder)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)} on the class — the realestate gate
 *       (matchIfMissing defaults to <strong>false</strong>, the {@link RealEstateAutoConfiguration}
 *       posture);</li>
 *   <li>{@code @ConditionalOnBean(DefaultHandoffIntentHandler.class)} — the responder gate: the E2
 *       responder beans (incl. the {@link DefaultHandoffIntentHandler}) exist <strong>only</strong> when
 *       {@code kmosf.modules.responder.enabled} (matchIfMissing=true) is on (they are {@code @Bean}s in
 *       {@link ResponderAutoConfiguration}). A deployment that disables responder has no default-handoff
 *       bean → this whole config (and the handoff delegate) is absent. {@code @AutoConfiguration(after=...)}
 *       guarantees both prerequisite configs are processed first.</li>
 * </ul>
 * Net: realestate ON + responder ON ⇒ active; either OFF ⇒ absent (a hard no-op — the
 * {@code MidnightResponderModuleGateIT} proves both directions). Per-tenant membership is enforced by
 * {@code TenantModuleRegistry.requireEnabled} for both keys in the controllers, and re-checked in
 * {@link TierRoutingService#process} (defense-in-depth on the event path).
 *
 * <h2>What it wires (hand-constructed beans — the realestate-module {@code @Value} lesson)</h2>
 * <ul>
 *   <li>{@link TierRoutingService} — the {@code LEAD_SCORE_UPDATED} WARM/COLD nurture auto-enroll
 *       subscriber (HOT stays the RE-2 {@code LeadHandoffService}); its {@code @PostConstruct} fires the
 *       bus subscription at init.</li>
 *   <li>{@link ResponderHandoffDelegateImpl} — the off-listing / unknown-intent handoff over the E2
 *       {@code DefaultHandoffIntentHandler}.</li>
 *   <li>{@link #midnightResponderHandoffWiring} — a side-effecting bean (the
 *       {@code ConciergeInboundSmsWiring} / {@code ResponderInboundSmsWiring} precedent) that calls
 *       {@code conciergeInboundRouter.setResponderHandoff(delegate)} at singleton init, flipping the
 *       off-listing/HANDOFF delegation live. Without it the router's delegate stays null (= byte-identical
 *       RE-1).</li>
 * </ul>
 *
 * <p><strong>Error band 4380-4389</strong> (the {@code GlobalErrorHandler} Javadoc table). 4380 config
 * not-found; 4381 invalid config (tier campaign id not a tenant campaign); 4382 advisory (tier-route enroll
 * into a missing/inactive campaign — logged, never thrown). 4383-4389 reserved.
 */
@AutoConfiguration(after = {RealEstateAutoConfiguration.class, ResponderAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@ConditionalOnBean(DefaultHandoffIntentHandler.class)
public class RealEstateMidnightAutoConfiguration {

    /**
     * The tier-routing subscriber: a {@code LEAD_SCORE_UPDATED} listener that auto-enrolls a WARM/COLD
     * concierge-sourced realestate lead into the tenant's configured nurture campaign (HOT is the RE-2
     * hot-handoff's job, untouched). Idempotent via the E1 {@code tenant_campaign_contact_idx}.
     */
    @Bean
    public TierRoutingService midnightTierRoutingService(
            DomainEventPublisher events,
            TenantRepository tenants,
            DealRepository deals,
            MidnightResponderConfigRepository configs,
            NurtureCampaignRepository campaigns,
            NurtureEnrollmentRepository enrollments) {
        return new TierRoutingService(events, tenants, deals, configs, campaigns, enrollments);
    }

    /**
     * The off-listing / unknown-intent handoff delegate over the E2 {@link DefaultHandoffIntentHandler}.
     * Holds the config repo so it owns the per-tenant NO_LISTING-always / HANDOFF-iff-flag policy.
     */
    @Bean
    public ResponderHandoffDelegate midnightResponderHandoffDelegate(
            DefaultHandoffIntentHandler defaultHandoffIntentHandler,
            TwilioSmsService twilioSmsService,
            MidnightResponderConfigRepository configs) {
        return new ResponderHandoffDelegateImpl(defaultHandoffIntentHandler, twilioSmsService, configs);
    }

    /**
     * Wires the {@link ResponderHandoffDelegate} onto the {@link ConciergeInboundRouter} (present because
     * realestate is on), flipping the off-listing/HANDOFF delegation live. Returns a tiny marker; the side
     * effect is the setter call at singleton init. Without this, the seam would always see a null delegate
     * and never delegate (= byte-identical RE-1). The {@code ConciergeInboundSmsWiring} precedent.
     */
    @Bean
    public MidnightResponderHandoffWiring midnightResponderHandoffWiring(
            ConciergeInboundRouter conciergeInboundRouter,
            ResponderHandoffDelegate midnightResponderHandoffDelegate) {
        conciergeInboundRouter.setResponderHandoff(midnightResponderHandoffDelegate);
        return new MidnightResponderHandoffWiring();
    }

    /** Marker for the {@link #midnightResponderHandoffWiring} side-effecting wiring bean. */
    public static final class MidnightResponderHandoffWiring {
    }
}
