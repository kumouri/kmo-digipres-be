package com.kumouri.kmodigipresbe.module.responder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;
import com.kumouri.kmodigipresbe.model.responder.ReplyLogEntry;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 — InboundIntentRouterIT: the orchestrator. Proves a registered vertical {@link IntentHandler}
 * matches + replies (SMS sent, ReplyLogEntry written, RESPONDER_INTENT_HANDLED fired); a no-match /
 * UNKNOWN routes to the default handoff (RESPONDER_HANDED_OFF + a generic reply); an opted-out sender
 * gets no reply (zero TwilioSmsService calls); the reply cap halts further replies; and — the keystone
 * — a tenant with NO ResponderConfig (or disabled / empty intents) → IGNORED with ZERO effect (the
 * default-tenant-unchanged proof).
 *
 * <p>Anthropic → WireMock; {@link TwilioSmsService} → a {@code @MockitoBean} capture seam (the
 * GapFillWaitlistIT precedent). A test {@link IntentHandler} bean is contributed via
 * {@link TestHandlerConfig} to prove the router auto-discovers handlers WITHOUT a router edit. No live
 * external (§7).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, InboundIntentRouterIT.TestHandlerConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class InboundIntentRouterIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-router-fake";
    private static final String CALLBACK_INTENT = "CALLBACK_REQUEST";
    private static final String TEST_HANDLER_REPLY = "Got it — we'll call you right back!";

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
    @Autowired ResponderConfigRepository configs;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private TenantContext ctx;
    private static final String SENDER = "+16185550200";

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), ResponderConfig.class).block();
        mongo.remove(new Query(), ConversationState.class).block();
        mongo.remove(new Query(), ReplyLogEntry.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        mongo.save(Tenant.builder().id(tenantId).slug("router-it-" + tenantId)
                .displayName("Router IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedTwilio(tenantId);
        seedAnthropic(tenantId);
    }

    // ── 1. A registered handler matches → reply sent, log written, events fired ──

    @Test
    void matchedHandler_replies_logsReply_firesHandledEvent() {
        seedConfig(5);
        stubClassify(CALLBACK_INTENT, 0.9);
        List<DomainEvent> handled = subscribe(DomainEventType.RESPONDER_INTENT_HANDLED);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "call me back")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);
        // The test handler's reply went out via Twilio.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).isEqualTo(TEST_HANDLER_REPLY);
        // A reply-cap ledger row was written.
        assertThat(mongo.findAll(ReplyLogEntry.class).collectList().block()).hasSize(1);
        // Conversation state created + turn recorded.
        ConversationState state = mongo.findAll(ConversationState.class).collectList().block().get(0);
        assertThat(state.getTurnCount()).isEqualTo(1);
        assertThat(state.getCurrentIntent()).isEqualTo(CALLBACK_INTENT);
        // The handled event fired with the test handler's key.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(handled).hasSize(1);
            assertThat(handled.get(0).payload().get("handlerKey")).isEqualTo(TestCallbackHandler.KEY);
            assertThat(handled.get(0).payload().get("replied")).isEqualTo(true);
        });
    }

    // ── 2. UNKNOWN / no-match → default handoff (generic reply + RESPONDER_HANDED_OFF) ──

    @Test
    void unknownIntent_defaultHandoff_genericReply_firesHandoffEvent() {
        seedConfig(5);
        // Model returns a label not in the configured set → classifier degrades to UNKNOWN.
        stubClassifyRaw("{\"intent\":\"SOMETHING_ELSE\",\"confidence\":0.4,\"extractedSlots\":{}}");
        List<DomainEvent> handoff = subscribe(DomainEventType.RESPONDER_HANDED_OFF);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "huh?")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);
        // Two SMS: the generic handoff REPLY to the sender + the staff NOTIFY to notifyPhone.
        SmsCommunicationRequest replyToSender = sentSms.stream()
                .filter(s -> SENDER.equals(s.to().e164())).findFirst().orElseThrow();
        assertThat(replyToSender.body()).isNotEqualTo(TEST_HANDLER_REPLY);
        assertThat(replyToSender.body().toLowerCase()).contains("team member");
        // The staff notify also went out (to the per-tenant notifyPhone, NOT the sender).
        assertThat(sentSms.stream().anyMatch(s -> "+16185550999".equals(s.to().e164()))).isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(handoff).hasSize(1));
    }

    // ── 3. Opted-out sender → no reply at all ──

    @Test
    void optedOutSender_noReply_ignored() {
        seedConfig(5);
        seedContact(SENDER, Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));
        stubClassify(CALLBACK_INTENT, 0.9);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "call me")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.IGNORED);
        assertThat(sentSms).isEmpty();
        // No classify happened either (opt-out gate precedes the classifier).
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
        assertThat(mongo.findAll(ReplyLogEntry.class).collectList().block()).isEmpty();
    }

    // ── 4. Reply cap reached → handled but no further reply ──

    @Test
    void replyCapReached_handledButNoReply() {
        seedConfig(1);
        // Pre-seed one reply already sent today → at the cap of 1.
        mongo.save(ReplyLogEntry.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .phone(SENDER).sentAt(Instant.now().minusSeconds(60)).build()).block();
        stubClassify(CALLBACK_INTENT, 0.9);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "call me")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // The handler still ran (HANDLED), but the cap suppressed the outbound reply.
        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);
        assertThat(sentSms).isEmpty();
        // Still exactly the pre-seeded one row (no new reply logged).
        assertThat(mongo.findAll(ReplyLogEntry.class).collectList().block()).hasSize(1);
    }

    // ── 5. THE KEYSTONE — no ResponderConfig → IGNORED, zero effect (default-tenant-unchanged) ──

    @Test
    void noConfig_ignored_zeroEffect() {
        // No ResponderConfig seeded for this tenant.
        stubClassify(CALLBACK_INTENT, 0.9);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "call me")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.IGNORED);
        assertThat(sentSms).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
        assertThat(mongo.findAll(ConversationState.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(ReplyLogEntry.class).collectList().block()).isEmpty();
    }

    @Test
    void disabledConfig_ignored_zeroEffect() {
        ResponderConfig cfg = baseConfig(5);
        cfg.setEnabled(false);
        configs.save(cfg).block();
        stubClassify(CALLBACK_INTENT, 0.9);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "call me")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.IGNORED);
        assertThat(sentSms).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    @Test
    void emptyIntentsConfig_ignored_zeroEffect() {
        ResponderConfig cfg = baseConfig(5);
        cfg.setIntents(new ArrayList<>());
        configs.save(cfg).block();
        stubClassify(CALLBACK_INTENT, 0.9);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, "+16185550100", "call me")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.IGNORED);
        assertThat(sentSms).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    // ── helpers ──

    private List<DomainEvent> subscribe(String type) {
        List<DomainEvent> observed = new CopyOnWriteArrayList<>();
        eventPublisher.stream().filter(e -> type.equals(e.type())).subscribe(observed::add);
        return observed;
    }

    private void seedConfig(int replyCap) {
        configs.save(baseConfig(replyCap)).block();
    }

    private ResponderConfig baseConfig(int replyCap) {
        return ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .enabled(true).vertical("home")
                .intents(new ArrayList<>(List.of(
                        new IntentDefinition(CALLBACK_INTENT, "wants a callback"),
                        new IntentDefinition("PRICING_QUESTION", "asks price"))))
                .replyCapPerContactPerDay(replyCap)
                .build();
    }

    private void seedContact(String phone, Set<String> tags) {
        mongo.save(Contact.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON).firstName("Pat").displayName("Pat")
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(tags).build()).block();
    }

    private void seedTwilio(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", "twilio_test_router")))
                .config(new HashMap<>(Map.of("notifyEmail", "rob@example.test", "notifyPhone", "+16185550999")))
                .build()).block();
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubClassify(String intent, double confidence) {
        stubClassifyRaw("{\"intent\":\"" + intent + "\",\"confidence\":" + confidence
                + ",\"extractedSlots\":{}}");
    }

    private void stubClassifyRaw(String rawModelText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageJson(rawModelText))));
    }

    /** A valid Anthropic Messages response body carrying {@code text} as one text block (JSON-escaped). */
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

    /** A test vertical handler proving the router auto-discovers handlers without a router edit. */
    @TestConfiguration
    static class TestHandlerConfig {
        @Bean
        IntentHandler testCallbackHandler() {
            return new TestCallbackHandler();
        }
    }

    /** Matches the CALLBACK_REQUEST intent in the "home" vertical and replies with a fixed message. */
    static class TestCallbackHandler implements IntentHandler {
        static final String KEY = "test-callback";

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public boolean supports(String vertical, String intent) {
            return CALLBACK_INTENT.equals(intent);
        }

        @Override
        public Mono<HandlerResult> handle(HandlerContext ctx) {
            return Mono.just(HandlerResult.reply(TEST_HANDLER_REPLY));
        }
    }
}
