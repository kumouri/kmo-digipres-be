package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.ReplyLogEntry;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — opt-out honored end-to-end (the reused E2 consent gate). A caller who opted out (carries the CF-2
 * {@code sms-opt-out} tag, set upstream by the {@code InboundSmsService} STOP path) → the
 * {@link InboundIntentRouter} returns IGNORED with no reply and no handler effect, so NO {@link CallbackRequest}
 * is recorded. Proves the callback handler never bypasses consent.
 *
 * <p>§7: Anthropic → WireMock (never reached for an opted-out sender — the consent gate short-circuits
 * before classify); {@link TwilioSmsService} → {@code @MockitoBean}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class CallbackOptOutIT {

    private static final String CALLER = "+13145550501";
    private static final String BUSINESS = "+13145550399";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void anthropicProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired InboundIntentRouter router;
    @Autowired ResponderConfigRepository responderConfigs;
    @Autowired ContactRepository contacts;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        for (Class<?> c : List.of(ResponderConfig.class, ConversationState.class, ReplyLogEntry.class,
                CallbackRequest.class, Contact.class, IntegrationConnection.class, Tenant.class)) {
            mongo.remove(new Query(), c).block();
        }
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        mongo.save(Tenant.builder().id(tenantId).slug("callback-optout-it-" + tenantId)
                .displayName("Callback Opt-Out IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("home-services", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedTwilio();
        seedAnthropic();
        seedHomeResponderConfig();
        // A canned classify is stubbed so that — IF the router did NOT short-circuit on opt-out —
        // the message would classify as a callback and create a card. The test proves it does NOT.
        stubClassifyScheduled();
    }

    @Test
    void optedOutCaller_reply_isIgnored_noCallbackRecorded() {
        // The caller already opted out (the InboundSmsService STOP path tagged them sms-opt-out).
        contacts.save(Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName("Opted-out caller")
                .phones(List.of(PhoneNumber.builder().number(CALLER).label("voicemail").build()))
                .tags(Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG))
                .build()).block();

        InboundIntentRouter.Outcome outcome = router
                .handle(tenantId, CALLER, BUSINESS, "in 30 min")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.IGNORED);
        assertThat(sentSms).isEmpty();                                   // no reply to an opted-out sender
        assertThat(mongo.findAll(CallbackRequest.class).collectList().block()).isEmpty();  // no card
    }

    // ── helpers ──

    private void seedHomeResponderConfig() {
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
                .vertical(CallbackIntents.VERTICAL)
                .intents(CallbackIntents.DEFAULTS)
                .systemPromptOverride(CallbackIntents.HOME_CALLBACK_CLASSIFIER_PROMPT)
                .replyCapPerContactPerDay(8)
                .build()).block();
    }

    private void seedTwilio() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", "twilio_test_callback")))
                .config(new HashMap<>(Map.of("notifyPhone", "+16185550999")))
                .build()).block();
    }

    private void seedAnthropic() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", "sk-ant-test-callback-optout")))
                .build()).block();
    }

    private void stubClassifyScheduled() {
        String raw = "{\"intent\":\"" + CallbackIntents.CALLBACK_SCHEDULED
                + "\",\"confidence\":0.95,\"extractedSlots\":{\"preferredTime\":\"in 30 min\"}}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageJson(raw))));
    }

    private static String messageJson(String text) {
        String escaped;
        try {
            escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + escaped + "}],"
                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":12}}";
    }
}
