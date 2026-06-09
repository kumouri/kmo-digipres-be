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
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * T5 — {@link CallbackIntentHandler} via the E2 {@link InboundIntentRouter}: a caller's callback reply is
 * routed (vertical=home + the callback intents) and recorded as a revenue-ranked {@link CallbackRequest}.
 * Mirrors {@code SwitchboardLogisticsIT} (drive the router directly, WireMock-classify the intent).
 *
 * <p>Proves: a "in 30 min" reply → routed to the callback handler → a SCHEDULED CallbackRequest with the
 * window text + parsed requestedAt + the revenue signal pulled from the prior DRAFT WorkOrder + an ACCEPTED
 * funnel row + a confirmation reply; a re-reply on the same CallSid updates the card in place (no second).
 *
 * <p>§7: Anthropic → WireMock; {@link TwilioSmsService} → {@code @MockitoBean}; sandbox key.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class CallbackReplyIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-callback-reply";
    private static final String CALLER = "+13145550501";
    private static final String BUSINESS = "+13145550399";
    private static final String CALL_SID = "CA-reply-it-1";

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
    private UUID contactId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        for (Class<?> c : List.of(ResponderConfig.class, ConversationState.class, ReplyLogEntry.class,
                CallbackRequest.class, CallbackOfferLog.class, CallbackFunnelLog.class, CallbackConfig.class,
                WorkOrder.class, Contact.class, IntegrationConnection.class, Tenant.class)) {
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
        mongo.save(Tenant.builder().id(tenantId).slug("callback-reply-it-" + tenantId)
                .displayName("Callback Reply IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("home-services", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedTwilio();
        seedAnthropic();
        seedHomeResponderConfig();
    }

    @Test
    void scheduledReply_recordsRankedCallbackRequest_funnelAccepted_confirmReply() {
        Contact caller = seedCaller();
        seedOfferLogAndWorkOrder(caller.getId());          // the prior voicemail offer + the DRAFT WO
        stubClassifyScheduled("in 30 min");

        InboundIntentRouter.Outcome outcome = router
                .handle(tenantId, CALLER, BUSINESS, "in 30 min")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);

        // A single CallbackRequest, SCHEDULED, with the window + the revenue signal from the DRAFT WO.
        List<CallbackRequest> cards = mongo.findAll(CallbackRequest.class).collectList().block();
        assertThat(cards).hasSize(1);
        CallbackRequest card = cards.get(0);
        assertThat(card.getMode()).isEqualTo(CallbackMode.SCHEDULED);
        assertThat(card.getStatus()).isEqualTo(CallbackStatus.REQUESTED);
        assertThat(card.getRequestedWindowText()).isEqualTo("in 30 min");
        assertThat(card.getRequestedAt()).isNotNull();          // "in 30 min" parsed
        assertThat(card.getCallSid()).isEqualTo(CALL_SID);
        assertThat(card.getUrgency()).isEqualTo("EMERGENCY");   // from the DRAFT WO customFields
        assertThat(card.getJobValueBand()).isEqualTo("LARGE");
        assertThat(card.getRevenueScore()).isGreaterThan(0L);

        // An ACCEPTED funnel row + a confirmation reply was sent to the caller.
        assertThat(funnelCount(CallbackFunnelStage.ACCEPTED)).isEqualTo(1);
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(CALLER);
    }

    @Test
    void reReplySameCallSid_updatesInPlace_noDuplicateCard() {
        Contact caller = seedCaller();
        seedOfferLogAndWorkOrder(caller.getId());

        stubClassifyScheduled("in 30 min");
        router.handle(tenantId, CALLER, BUSINESS, "in 30 min")
                .contextWrite(TenantContextHolder.write(ctx)).block();

        stubClassifyScheduled("after 5pm");
        router.handle(tenantId, CALLER, BUSINESS, "actually after 5pm")
                .contextWrite(TenantContextHolder.write(ctx)).block();

        List<CallbackRequest> cards = mongo.findAll(CallbackRequest.class).collectList().block();
        assertThat(cards).hasSize(1);                                  // updated in place, not duplicated
        assertThat(cards.get(0).getRequestedWindowText()).isEqualTo("after 5pm");
    }

    // ── helpers ──

    private long funnelCount(CallbackFunnelStage stage) {
        return mongo.findAll(CallbackFunnelLog.class).collectList().block().stream()
                .filter(l -> l.getStage() == stage).count();
    }

    private void seedHomeResponderConfig() {
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
                .vertical(CallbackIntents.VERTICAL)
                .intents(CallbackIntents.DEFAULTS)
                .systemPromptOverride(CallbackIntents.HOME_CALLBACK_CLASSIFIER_PROMPT)
                .replyCapPerContactPerDay(8)
                .build()).block();
    }

    private Contact seedCaller() {
        Contact contact = contacts.save(Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName("Voicemail caller " + CALLER)
                .phones(List.of(PhoneNumber.builder().number(CALLER).label("voicemail").build()))
                .build()).block();
        contactId = contact.getId();
        return contact;
    }

    /** Seed the prior offer log (the reply→voicemail correlation) + a DRAFT WO carrying the revenue signal. */
    private void seedOfferLogAndWorkOrder(UUID callerContactId) {
        mongo.save(CallbackOfferLog.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).callSid(CALL_SID)
                .contactId(callerContactId).offeredAt(Instant.now())
                .build()).block();
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("urgency", "EMERGENCY");
        cf.put("jobValueBand", "LARGE");
        cf.put("callSid", CALL_SID);
        cf.put("source", "voicemail");
        mongo.save(WorkOrder.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(WorkOrderStatus.DRAFT).serviceType("HVAC")
                .title("HVAC — EMERGENCY").customFields(cf)
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
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubClassifyScheduled(String preferredTime) {
        String slots = "{\"preferredTime\":\"" + preferredTime + "\"}";
        String raw = "{\"intent\":\"" + CallbackIntents.CALLBACK_SCHEDULED
                + "\",\"confidence\":0.95,\"extractedSlots\":" + slots + "}";
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
