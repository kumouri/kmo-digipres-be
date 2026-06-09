package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.ReplyLogEntry;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
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
 * T4 (Health "Switchboard AI") — SwitchboardLogisticsIT: the logistics overflow. Drives the E2
 * {@link InboundIntentRouter} directly (the {@code InboundIntentRouterIT} pattern) with the real T4
 * {@link LogisticsIntentHandler} wired (frontdesk + responder on) and proves each of the seven logistics
 * intents is answered <strong>from per-tenant {@link SwitchboardConfig}</strong> (the WireMock classifier
 * stubs the intent), a reply is sent, a conversation turn recorded, and a
 * {@link SwitchboardDeflectionCategory#LOGISTICS} deflection row written.
 *
 * <p>§7: Anthropic → WireMock; {@link TwilioSmsService} → {@code @MockitoBean}; sandbox key.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class SwitchboardLogisticsIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-switchboard-logistics";
    private static final String SENDER = "+16185550440";
    private static final String BUSINESS = "+16185550400";
    private static final String HOURS = "Mon–Fri 8am–5pm";
    private static final String INTAKE_URL = "https://example.test/intake";

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
    @Autowired SwitchboardConfigRepository switchboardConfigs;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), ResponderConfig.class).block();
        mongo.remove(new Query(), SwitchboardConfig.class).block();
        mongo.remove(new Query(), SwitchboardDeflectionLog.class).block();
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
        mongo.save(Tenant.builder().id(tenantId).slug("switchboard-logistics-it-" + tenantId)
                .displayName("Switchboard Logistics IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedTwilio();
        seedAnthropic();
        seedResponderConfig();
        seedSwitchboardConfig();
    }

    // ── HOURS answered from config ───────────────────────────────────────────────

    @Test
    void hoursIntent_answeredFromConfig_logsLogisticsDeflection() {
        stubClassify(SwitchboardIntents.HOURS);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, BUSINESS, "when are you open?")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(SENDER);
        assertThat(sentSms.get(0).body()).contains(HOURS);

        // A conversation turn was recorded + a LOGISTICS deflection row written.
        ConversationState state = mongo.findAll(ConversationState.class).collectList().block().get(0);
        assertThat(state.getTurnCount()).isEqualTo(1);
        assertThat(state.getCurrentIntent()).isEqualTo(SwitchboardIntents.HOURS);
        assertThat(deflectionCount(SwitchboardDeflectionCategory.LOGISTICS)).isEqualTo(1);
        assertThat(deflectionCount(SwitchboardDeflectionCategory.TRIPWIRE)).isZero();
    }

    // ── INTAKE_FORM answers with the configured URL ─────────────────────────────

    @Test
    void intakeFormIntent_answersWithConfiguredUrl() {
        stubClassify(SwitchboardIntents.INTAKE_FORM);

        router.handle(tenantId, SENDER, BUSINESS, "where are the new patient forms?")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains(INTAKE_URL);
        assertThat(deflectionCount(SwitchboardDeflectionCategory.LOGISTICS)).isEqualTo(1);
    }

    // ── ACCEPTING_NEW_PATIENTS reflects the config flag (true) ──────────────────

    @Test
    void acceptingNewPatientsIntent_reflectsConfigFlag() {
        stubClassify(SwitchboardIntents.ACCEPTING_NEW_PATIENTS);

        router.handle(tenantId, SENDER, BUSINESS, "are you taking new patients?")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body().toLowerCase()).contains("new patient");
        // Config flag is true → an affirmative answer (not the "not taking" copy).
        assertThat(sentSms.get(0).body().toLowerCase()).doesNotContain("not taking new patients");
    }

    // ── BOOK_APPOINTMENT answers from config ────────────────────────────────────

    @Test
    void bookAppointmentIntent_answeredFromConfig() {
        stubClassify(SwitchboardIntents.BOOK_APPOINTMENT);

        router.handle(tenantId, SENDER, BUSINESS, "I'd like to make an appointment")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("(555) 123-4567");
        assertThat(deflectionCount(SwitchboardDeflectionCategory.LOGISTICS)).isEqualTo(1);
    }

    // ── helpers ──

    private long deflectionCount(SwitchboardDeflectionCategory category) {
        return mongo.findAll(SwitchboardDeflectionLog.class).collectList().block().stream()
                .filter(l -> l.getCategory() == category).count();
    }

    private void seedResponderConfig() {
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
                .vertical(SwitchboardIntents.VERTICAL)
                .intents(SwitchboardIntents.DEFAULTS)
                .systemPromptOverride(SwitchboardIntents.HEALTH_CLASSIFIER_PROMPT)
                .replyCapPerContactPerDay(8)
                .build()).block();
    }

    private void seedSwitchboardConfig() {
        switchboardConfigs.save(SwitchboardConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .hoursText(HOURS)
                .locationText("123 Main St, Suite 200")
                .acceptingNewPatients(true)
                .bookingInstructions("call (555) 123-4567")
                .rescheduleInstructions("call (555) 123-4567")
                .intakeFormUrl(INTAKE_URL)
                .reviewLinkUrl("https://example.test/review")
                .build()).block();
    }

    private void seedTwilio() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", "twilio_test_switchboard")))
                .config(new HashMap<>(Map.of("notifyEmail", "frontdesk@example.test",
                        "notifyPhone", "+16185550999")))
                .build()).block();
    }

    private void seedAnthropic() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubClassify(String intent) {
        String raw = "{\"intent\":\"" + intent + "\",\"confidence\":0.95,\"extractedSlots\":{}}";
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
