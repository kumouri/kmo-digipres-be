package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.ReplyLogEntry;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 (Health "Switchboard AI") — SwitchboardTripwireIT: <strong>the marquee, release-blocking PHI
 * fence</strong>. Drives the E2 {@link InboundIntentRouter} directly with the real T4
 * {@link ClinicalTripwireHandler} wired (frontdesk + responder on); a clinical/symptom inbound (the
 * WireMock classifier returns {@link SwitchboardIntents#CLINICAL_SYMPTOM} with empty
 * {@code extractedSlots} — the per-tenant PHI-forbidding prompt's contract) must yield:
 * <ol>
 *   <li>a safe handoff reply texted back + a staff notify (email + SMS);</li>
 *   <li>a redaction-only callback {@link Activity} (body = the fixed marker, never the patient's words);</li>
 *   <li>a {@link SwitchboardDeflectionCategory#TRIPWIRE} deflection row;</li>
 *   <li><strong>the headline</strong>: a full serialize-and-scan of the persisted {@link ConversationState}
 *       + every {@link Activity} + the deflection log + the reply-log contains <strong>NONE</strong> of the
 *       distinctive clinical tokens from the inbound body — the inbound text is nowhere persisted.</li>
 * </ol>
 * The forbidden-token serialize-and-scan technique mirrors {@code HealthFrontDeskVoicemailIT} (FD-2 fence
 * F2). §7: Anthropic → WireMock; {@link TwilioSmsService} + {@link EmailService} → {@code @MockitoBean}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class SwitchboardTripwireIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-switchboard-tripwire";
    private static final String SENDER = "+16185550450";
    private static final String BUSINESS = "+16185550400";
    private static final String NOTIFY_EMAIL = "frontdesk@cedar-clinic.test";
    private static final String NOTIFY_PHONE = "+16185550999";

    /**
     * The clinical inbound. It deliberately carries distinctive CLINICAL tokens — "chest pain",
     * "Lisinopril" (a drug), "dizzy" (a symptom) — none of which appear in any logistics/redaction
     * vocabulary, so they are a sound forbidden-token set for the PHI assertion.
     */
    private static final String CLINICAL_INBOUND =
            "I've had chest pain since this morning and I feel dizzy, I take Lisinopril, should I come in?";

    private static final List<String> FORBIDDEN_CLINICAL_TOKENS =
            List.of("chest pain", "lisinopril", "dizzy");

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
    @Autowired ObjectMapper objectMapper;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();
    private final AtomicReference<String> emailBody = new AtomicReference<>();
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
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sentSms.clear();
        emailTo.set(null);
        emailBody.set(null);

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });
        org.mockito.Mockito.when(emailService.sendSingleEmail(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    SingleEmailCommunicationRequest req = inv.getArgument(0);
                    emailTo.set(req.to() == null ? null : req.to().asString());
                    emailBody.set(req.body());
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        mongo.save(Tenant.builder().id(tenantId).slug("switchboard-tripwire-it-" + tenantId)
                .displayName("Switchboard Tripwire IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedTwilio();
        seedAnthropic();
        seedResponderConfig();
        seedSwitchboardConfig();
    }

    // ── THE MARQUEE / release-blocking F-fence test ──────────────────────────────

    @Test
    void symptomInbound_handsOff_persistsNoClinicalText() {
        // The configured PHI-forbidding prompt's contract: clinical → CLINICAL_SYMPTOM + EMPTY slots.
        stubClassify(SwitchboardIntents.CLINICAL_SYMPTOM);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, BUSINESS, CLINICAL_INBOUND)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);

        // (1) A safe handoff reply went back to the patient (NOT a clinical answer).
        SmsCommunicationRequest replyToSender = sentSms.stream()
                .filter(s -> SENDER.equals(s.to().e164())).findFirst().orElseThrow();
        assertThat(replyToSender.body().toLowerCase()).contains("call");
        assertScrubbed(replyToSender.body());

        // (1b) Staff was notified (email + SMS to the per-tenant notify targets) — PHI-free.
        assertThat(sentSms.stream().anyMatch(s -> NOTIFY_PHONE.equals(s.to().e164()))).isTrue();
        assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
        assertScrubbed(emailBody.get());
        sentSms.forEach(s -> assertScrubbed(s.body()));

        // (2) Exactly one redaction-only callback Activity — body is the fixed marker, never the inbound.
        List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
        assertThat(acts).as("exactly one tripwire callback Activity").hasSize(1);
        Activity a = acts.get(0);
        assertThat(a.getBody()).isEqualTo(SwitchboardRedaction.CLINICAL_MESSAGE_REDACTED_MARKER);
        assertThat(a.getPayload()).containsEntry("redacted", true);

        // (3) A TRIPWIRE deflection row was written (and no LOGISTICS row).
        assertThat(deflectionCount(SwitchboardDeflectionCategory.TRIPWIRE)).isEqualTo(1);
        assertThat(deflectionCount(SwitchboardDeflectionCategory.LOGISTICS)).isZero();

        // (4) THE HEADLINE — the persisted conversation carries NO clinical content:
        //     empty slots + a category-label currentIntent (not the patient's words).
        ConversationState state = mongo.findAll(ConversationState.class).collectList().block().get(0);
        assertThat(state.getSlots()).as("no clinical slots persisted").isEmpty();
        assertThat(state.getCurrentIntent()).isEqualTo(SwitchboardIntents.CLINICAL_SYMPTOM);

        // (4b) A full serialize-and-scan of EVERY persisted record contains NONE of the clinical tokens.
        StringBuilder everything = new StringBuilder();
        mongo.findAll(ConversationState.class).collectList().block().forEach(c -> everything.append(serialize(c)));
        mongo.findAll(Activity.class).collectList().block().forEach(x -> everything.append(serialize(x)));
        mongo.findAll(SwitchboardDeflectionLog.class).collectList().block()
                .forEach(l -> everything.append(serialize(l)));
        mongo.findAll(ReplyLogEntry.class).collectList().block().forEach(r -> everything.append(serialize(r)));
        mongo.findAll(Contact.class).collectList().block().forEach(cn -> everything.append(serialize(cn)));
        assertScrubbed(everything.toString());
    }

    // ── Degrade: Anthropic 500 → classifier UNKNOWN → default handoff, still no leak ─────

    @Test
    void anthropicDown_degradesToHandoff_neverLeaks() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, BUSINESS, CLINICAL_INBOUND)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // The classifier degraded to UNKNOWN → the E2 default handoff handled it (a generic reply).
        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);
        SmsCommunicationRequest replyToSender = sentSms.stream()
                .filter(s -> SENDER.equals(s.to().e164())).findFirst().orElseThrow();
        assertThat(replyToSender.body().toLowerCase()).contains("team member");

        // Even on the degraded path, nothing clinical is persisted anywhere (the body is never stored).
        StringBuilder everything = new StringBuilder();
        mongo.findAll(ConversationState.class).collectList().block().forEach(c -> everything.append(serialize(c)));
        mongo.findAll(Activity.class).collectList().block().forEach(x -> everything.append(serialize(x)));
        mongo.findAll(ReplyLogEntry.class).collectList().block().forEach(r -> everything.append(serialize(r)));
        assertScrubbed(everything.toString());
    }

    // ── helpers ──

    /** Asserts NONE of the forbidden clinical tokens appear in {@code text} (case-insensitive). */
    private static void assertScrubbed(String text) {
        String hay = (text == null ? "" : text).toLowerCase();
        for (String token : FORBIDDEN_CLINICAL_TOKENS) {
            assertThat(hay)
                    .as("clinical token '%s' must never be persisted/sent (PHI fence)", token)
                    .doesNotContain(token.toLowerCase());
        }
    }

    private long deflectionCount(SwitchboardDeflectionCategory category) {
        return mongo.findAll(SwitchboardDeflectionLog.class).collectList().block().stream()
                .filter(l -> l.getCategory() == category).count();
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                .hoursText("Mon–Fri 8am–5pm")
                .locationText("123 Main St")
                .acceptingNewPatients(true)
                .build()).block();
    }

    private void seedTwilio() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", "twilio_test_switchboard")))
                .config(new HashMap<>(Map.of("notifyEmail", NOTIFY_EMAIL, "notifyPhone", NOTIFY_PHONE)))
                .build()).block();
    }

    private void seedAnthropic() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubClassify(String intent) {
        String raw = "{\"intent\":\"" + intent + "\",\"confidence\":0.97,\"extractedSlots\":{}}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageJson(raw))));
    }

    private static String messageJson(String text) {
        String escaped;
        try {
            escaped = new ObjectMapper().writeValueAsString(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + escaped + "}],"
                + "\"usage\":{\"input_tokens\":80,\"output_tokens\":10}}";
    }
}
