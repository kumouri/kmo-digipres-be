package com.kumouri.kmodigipresbe.module.responder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.InboundSmsService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ConversationStateRepository;
import com.kumouri.kmodigipresbe.repository.responder.ReplyLogRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.responder.ConversationStateService;
import com.kumouri.kmodigipresbe.service.responder.DefaultHandoffIntentHandler;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentClassifier;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * E2 — the Inbound Responder + Intent Router module (the shared inbound-SMS responder engine). Loaded by
 * default ({@code @ConditionalOnProperty(prefix="kmosf.modules.responder", name="enabled",
 * matchIfMissing=true)}, the {@code NurtureAutoConfiguration} precedent); absent only when a deployment
 * explicitly sets {@code kmosf.modules.responder.enabled=false}. Per-tenant membership is then enforced
 * by {@code TenantModuleRegistry.requireEnabled("responder")} in {@code ResponderConfigController}.
 *
 * <h2>Blast radius zero</h2>
 * The engine only ACTIVATES for a tenant that has a usable {@code ResponderConfig} row — the
 * {@link InboundIntentRouter} returns {@code IGNORED} for absent/disabled/empty config, so the shipped
 * inbound-SMS path ({@code InboundSmsService} STOP / YES / realestate) is byte-identical and the
 * {@code GapFillWaitlistIT} / {@code RealEstateConciergeIT} regression gates pass unchanged. Beans are
 * hand-constructed (not component-scanned) so the {@code @Value}-resolved config lands on factory params
 * (the chairfill/salon/realestate lesson).
 *
 * <h2>Inbound-SMS wiring (the key cross-module seam)</h2>
 * {@code InboundSmsService} is constructed only by ChairFill / Real-Estate. So:
 * <ul>
 *   <li>{@link #responderInboundSmsService} contributes a {@code @ConditionalOnMissingBean} fallback
 *       {@code InboundSmsService} — an EXACT mirror of {@code RealEstateAutoConfiguration}'s fallback —
 *       so a responder-only deployment (chairfill + realestate both off) still has the inbound webhook
 *       service. When chairfill/realestate is also on, their bean wins ({@code @ConditionalOnMissingBean})
 *       and the router is wired onto it anyway.</li>
 *   <li>{@link #responderInboundSmsWiring} is a side-effecting bean (the {@code ConciergeInboundSmsWiring}
 *       mirror) that calls {@code inboundSmsService.setIntentRouter(router)} at singleton init so the
 *       {@code IGNORED} fallthrough delegates.</li>
 * </ul>
 * {@code @AutoConfiguration(after = {ChairFillAutoConfiguration, RealEstateAutoConfiguration})} so the
 * {@code @ConditionalOnMissingBean} correctly defers to their beans and the wiring runs after the winning
 * {@code InboundSmsService} exists.
 */
@AutoConfiguration(after = {ChairFillAutoConfiguration.class, RealEstateAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.responder", name = "enabled", matchIfMissing = true)
public class ResponderAutoConfiguration {

    public static final String MODULE_KEY = "responder";

    @Bean
    public ModuleDefinition responderModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Inbound Responder + Intent Router", "0.1.0",
                List.of("RESPONDER_CONFIG", "RESPONDER_CONVERSATION"));
    }

    /**
     * The free-text intent classifier (the {@code VoicemailExtractionService} transport shape).
     * Hand-built so the {@code @Value}-resolved key/base-url/model land on the factory params.
     */
    @Bean
    public InboundIntentClassifier inboundIntentClassifier(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.responder.classify-model:claude-haiku-4-5}") String classifyModel,
            @Value("${kmosf.responder.classify-system-prompt:}") String systemPromptOverride) {
        return new InboundIntentClassifier(webClientBuilder, objectMapper, connections, usageRecorder,
                baseUrl, houseKey, classifyModel, systemPromptOverride);
    }

    /** Lightweight conversation state (keyed (tenant, phone), TTL-evicted). */
    @Bean
    public ConversationStateService conversationStateService(
            ConversationStateRepository conversations,
            @Value("${kmosf.responder.conversation-ttl:PT24H}") Duration conversationTtl) {
        return new ConversationStateService(conversations, conversationTtl);
    }

    /**
     * The built-in default handoff handler (the universal fallback). A {@code @ConditionalOnMissingBean}
     * is NOT used — a deployment adds vertical handlers as ADDITIONAL {@code IntentHandler} beans; the
     * router auto-discovers all of them and uses this only as the fallback.
     */
    @Bean
    public DefaultHandoffIntentHandler defaultHandoffIntentHandler(
            IntegrationConnectionRepository connections,
            TwilioSmsService twilioSmsService,
            EmailService emailService,
            DomainEventPublisher events,
            @Value("${kmosf.responder.handoff-reply:Thanks for your message! A team member will follow "
                    + "up with you shortly.}") String handoffReply,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        return new DefaultHandoffIntentHandler(connections, twilioSmsService, emailService, events,
                handoffReply, notifyFromAddress);
    }

    /**
     * The orchestrator the {@code InboundSmsService} IGNORED-fallthrough delegates to. Auto-discovers
     * every {@link IntentHandler} bean (the registry) via the {@code List<IntentHandler>} inject, so a
     * later vertical handler is registered WITHOUT touching the router.
     */
    @Bean
    public InboundIntentRouter inboundIntentRouter(
            ResponderConfigRepository configs,
            ContactRepository contacts,
            ReplyLogRepository replyLog,
            ConversationStateService conversationStateService,
            InboundIntentClassifier classifier,
            List<IntentHandler> handlers,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events) {
        return new InboundIntentRouter(configs, contacts, replyLog, conversationStateService, classifier,
                handlers, twilioSmsService, events);
    }

    /**
     * Fallback {@code InboundSmsService} for a <strong>responder-only</strong> deployment (chairfill +
     * realestate both off, so neither contributes the bean). EXACT mirror of
     * {@code RealEstateAutoConfiguration.realEstateInboundSmsService}: the inbound webhook + STOP/opt-out
     * still work; the YES path is unreachable (no {@code WaitlistClaimService}); the responder router is
     * wired onto it below. {@code @ConditionalOnMissingBean} → chairfill/realestate's bean wins when on.
     */
    @Bean
    @ConditionalOnMissingBean(InboundSmsService.class)
    public InboundSmsService responderInboundSmsService(
            IntegrationConnectionRepository connections,
            ContactRepository contacts) {
        return new InboundSmsService(connections, contacts);
    }

    /**
     * Wires the {@link InboundIntentRouter} onto whichever {@link InboundSmsService} bean is present
     * (ChairFill's, Real-Estate's fallback, or the responder fallback above), flipping the E2 generic
     * responder seam live. Returns a tiny marker; the side effect is the setter call at singleton init.
     * Without this, the seam would always see a null router and never delegate (= byte-identical to today).
     * The {@code ConciergeInboundSmsWiring} precedent.
     */
    @Bean
    public ResponderInboundSmsWiring responderInboundSmsWiring(
            InboundSmsService inboundSmsService,
            InboundIntentRouter inboundIntentRouter) {
        inboundSmsService.setIntentRouter(inboundIntentRouter);
        return new ResponderInboundSmsWiring();
    }

    /** Marker for the {@link #responderInboundSmsWiring} side-effecting wiring bean. */
    public static final class ResponderInboundSmsWiring {
    }
}
