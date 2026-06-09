package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T5 (Home Services "Instant Callback") — the both-module auto-configuration that deploys the E2 inbound
 * responder to the home-services vertical as a callback opt-in loop + a revenue-ranked dispatcher queue +
 * recovery analytics. The {@code SwitchboardAutoConfiguration} (T4) / {@code RealEstateMidnightAutoConfiguration}
 * (T3) precedent — here for home-services + responder.
 *
 * <h2>Both-modules gate (home-services AND responder)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.home-services.enabled)} on the class — the home-services
 *       gate (matchIfMissing defaults to <strong>false</strong>, the {@link HomeServicesAutoConfiguration}
 *       posture);</li>
 *   <li>{@code @ConditionalOnBean(InboundIntentRouter.class)} — the responder gate: the E2 responder beans
 *       (incl. the {@link InboundIntentRouter}) exist <strong>only</strong> when
 *       {@code kmosf.modules.responder.enabled} (matchIfMissing=true) is on (they are {@code @Bean}s in
 *       {@link ResponderAutoConfiguration}). A deployment that disables responder has no router bean → this
 *       whole config is absent. {@code @AutoConfiguration(after=...)} guarantees both prerequisite configs
 *       are processed first.</li>
 * </ul>
 * Net: home-services ON + responder ON ⇒ active; either OFF ⇒ absent (a hard no-op). Per-tenant membership
 * is enforced by {@code TenantModuleRegistry.requireEnabled} for both keys in {@link CallbackController}.
 *
 * <h2>Blast radius zero — the handler self-registers, no E2 edit</h2>
 * The {@link CallbackIntentHandler} bean is auto-discovered by the {@link InboundIntentRouter}'s
 * {@code List<IntentHandler>} inject purely by being a bean (the {@code LogisticsIntentHandler} /
 * {@code DefaultHandoffIntentHandler} precedent) — the router needs NO edit. It activates only for a tenant
 * whose {@code ResponderConfig(vertical="home")} names the callback intents; every E2 / home-services core
 * stays byte-identical. The reused voicemail core ({@code TwilioVoicemailService}) is empty-diff — the
 * {@link CallbackOfferSubscriber} hooks off its already-published {@code VOICEMAIL_LEAD_CREATED} event
 * (no seam). Beans are hand-constructed (not component-scanned) so this config owns their wiring.
 *
 * <h2>The caller-facing offer SMS is default-OFF (§7)</h2>
 * The {@link CallbackOfferSubscriber} bean is created only when
 * {@code kmosf.modules.home-callback-offer.enabled=true} (matchIfMissing <strong>false</strong>, the
 * {@code ArAgingSweepJob} / {@code NurtureRunner} comms-runner precedent) — so absent the flag NO offer
 * SMS is ever sent in CI / any default run. The handler + the {@link CallbackController} (queue / dispatch
 * / recovery-stats / config) run on the both-modules gate; only the outbound caller send carries this
 * extra default-OFF gate. Going live also needs A2P 10DLC for the caller SMS (a separate human action).
 *
 * <p><strong>Error band 4400-4409</strong> (the {@code GlobalErrorHandler} Javadoc table). 4400 callback
 * not found; 4401 config not found (read); 4402 callback not REQUESTED (dispatch); 4403 invalid config.
 * 4404-4409 reserved.
 */
@AutoConfiguration(after = {HomeServicesAutoConfiguration.class, ResponderAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@ConditionalOnBean(InboundIntentRouter.class)
public class CallbackAutoConfiguration {

    /**
     * The home-callback E2 intent handler. Registered into the responder router's {@code List<IntentHandler>}
     * purely by being a bean — no router edit. Activates only for a tenant with a
     * {@code ResponderConfig(vertical="home")} naming the callback intents.
     */
    @Bean
    public CallbackIntentHandler callbackIntentHandler(
            CallbackRequestRepository callbackRequests,
            CallbackOfferLogRepository offerLogs,
            CallbackFunnelLogRepository funnelLogs,
            CallbackConfigRepository configs,
            ContactRepository contacts,
            ActivityRepository activities,
            WorkOrderRepository workOrders,
            DomainEventPublisher events) {
        return new CallbackIntentHandler(callbackRequests, offerLogs, funnelLogs, configs,
                contacts, activities, workOrders, events);
    }

    /**
     * The callback opt-in SMS subscriber (the {@code VOICEMAIL_LEAD_CREATED} hook). <strong>Default-OFF</strong>
     * — only created when {@code kmosf.modules.home-callback-offer.enabled=true} (matchIfMissing=false), so
     * no live caller SMS in CI / any default run. Its {@code @PostConstruct} fires the bus subscription at
     * init. {@code TwilioVoicemailService} stays empty-diff (the event already fires; no seam).
     */
    @Bean
    @ConditionalOnProperty(prefix = "kmosf.modules.home-callback-offer", name = "enabled")
    public CallbackOfferSubscriber callbackOfferSubscriber(
            DomainEventPublisher events,
            ResponderConfigRepository responderConfigs,
            CallbackConfigRepository callbackConfigs,
            CallbackOfferLogRepository offerLogs,
            CallbackFunnelLogRepository funnelLogs,
            ContactRepository contacts,
            TwilioSmsService twilioSmsService) {
        return new CallbackOfferSubscriber(events, responderConfigs, callbackConfigs, offerLogs,
                funnelLogs, contacts, twilioSmsService);
    }
}
