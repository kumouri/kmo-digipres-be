package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T4 (Health "Switchboard AI") — the both-module auto-configuration that deploys the E2 inbound responder
 * to the frontdesk (health) vertical as a logistics-only, PHI-free front-desk overflow + a clinical-message
 * tripwire + deflection analytics. The {@code RealEstateMidnightAutoConfiguration} (T3) precedent — here for
 * frontdesk + responder.
 *
 * <h2>Both-modules gate (frontdesk AND responder)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.frontdesk.enabled)} on the class — the frontdesk gate
 *       (matchIfMissing defaults to <strong>false</strong>, the {@link FrontDeskAutoConfiguration} posture);</li>
 *   <li>{@code @ConditionalOnBean(InboundIntentRouter.class)} — the responder gate: the E2 responder beans
 *       (incl. the {@link InboundIntentRouter}) exist <strong>only</strong> when
 *       {@code kmosf.modules.responder.enabled} (matchIfMissing=true) is on (they are {@code @Bean}s in
 *       {@link ResponderAutoConfiguration}). A deployment that disables responder has no router bean → this
 *       whole config is absent. {@code @AutoConfiguration(after=...)} guarantees both prerequisite configs
 *       are processed first.</li>
 * </ul>
 * Net: frontdesk ON + responder ON ⇒ active; either OFF ⇒ absent (a hard no-op — the
 * {@code SwitchboardModuleGateIT} proves both directions). Per-tenant membership is enforced by
 * {@code TenantModuleRegistry.requireEnabled} for both keys in {@link SwitchboardController}.
 *
 * <h2>Blast radius zero — the handlers self-register, no E2 edit</h2>
 * The two {@code IntentHandler} beans ({@link LogisticsIntentHandler}, {@link ClinicalTripwireHandler}) are
 * auto-discovered by the {@link InboundIntentRouter}'s {@code List<IntentHandler>} inject purely by being
 * beans (the {@code RealEstateNurtureReplyHandler} / {@code DefaultHandoffIntentHandler} precedent) — the
 * router needs NO edit. They activate only for a tenant whose {@code ResponderConfig(vertical="health")}
 * names the corresponding intents; every E2 / frontdesk core stays byte-identical. Beans are hand-constructed
 * (not component-scanned) so the {@code @Value}-resolved notify-from address lands on the factory param (the
 * chairfill/salon/realestate lesson).
 *
 * <p><strong>Error band 4390-4399</strong> (the {@code GlobalErrorHandler} Javadoc table). 4390 tripwire
 * callback-activity write failed (advisory); 4391 config not found; 4392 invalid config. 4393-4399 reserved.
 */
@AutoConfiguration(after = {FrontDeskAutoConfiguration.class, ResponderAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@ConditionalOnBean(InboundIntentRouter.class)
public class SwitchboardAutoConfiguration {

    /** The PHI-free deflection-analytics ledger writer/reader. */
    @Bean
    public SwitchboardDeflectionService switchboardDeflectionService(
            SwitchboardDeflectionLogRepository deflectionLogs) {
        return new SwitchboardDeflectionService(deflectionLogs);
    }

    /**
     * The logistics-only intent handler (front-desk overflow). Registered into the responder router's
     * {@code List<IntentHandler>} purely by being a bean — no router edit.
     */
    @Bean
    public LogisticsIntentHandler switchboardLogisticsIntentHandler(
            SwitchboardConfigRepository switchboardConfigs,
            SwitchboardDeflectionService switchboardDeflectionService) {
        return new LogisticsIntentHandler(switchboardConfigs, switchboardDeflectionService);
    }

    /**
     * The clinical-message tripwire (the PHI fence). Registered into the responder router's
     * {@code List<IntentHandler>} purely by being a bean — no router edit. The notify-from address is the
     * SMTP username (the {@code DefaultHandoffIntentHandler} precedent).
     */
    @Bean
    public ClinicalTripwireHandler switchboardClinicalTripwireHandler(
            ContactRepository contacts,
            ActivityCrudService activityCrudService,
            IntegrationConnectionRepository connections,
            TwilioSmsService twilioSmsService,
            EmailService emailService,
            SwitchboardConfigRepository switchboardConfigs,
            SwitchboardDeflectionService switchboardDeflectionService,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        return new ClinicalTripwireHandler(contacts, activityCrudService, connections, twilioSmsService,
                emailService, switchboardConfigs, switchboardDeflectionService, notifyFromAddress);
    }

    /**
     * The HANDOFF deflection recorder ({@code RESPONDER_HANDED_OFF} subscriber, health-scoped). Its
     * {@code @PostConstruct} fires the bus subscription at init.
     */
    @Bean
    public SwitchboardDeflectionRecorder switchboardDeflectionRecorder(
            DomainEventPublisher events,
            ResponderConfigRepository responderConfigs,
            SwitchboardDeflectionService switchboardDeflectionService) {
        return new SwitchboardDeflectionRecorder(events, responderConfigs, switchboardDeflectionService);
    }
}
