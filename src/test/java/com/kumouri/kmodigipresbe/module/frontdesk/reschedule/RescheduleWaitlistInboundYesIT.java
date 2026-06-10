package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
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
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
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
import java.time.Duration;
import java.time.Instant;
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
 * T7 (Health "RescheduleFlow") — the inbound-YES wiring IT: drives the E2 {@link InboundIntentRouter}
 * directly (the {@code SwitchboardLogisticsIT} pattern) with the real T7 {@link RescheduleWaitlistIntentHandler}
 * wired (frontdesk + waitlist + responder on) and proves a patient's "yes" classifies to the affirmative
 * intent → claims the offered freed slot → the {@link FrontDeskSlotMaterializer} creates the PHI-free
 * {@link Appointment}. Also proves STOP/opt-out is honored upstream (an opted-out sender's "yes" is IGNORED
 * with NO claim).
 *
 * <p>§7: Anthropic classifier → WireMock; {@link TwilioSmsService} → {@code @MockitoBean}; sandbox key.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.waitlist.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class RescheduleWaitlistInboundYesIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-reschedule-yes";
    private static final String SENDER = "+16185550441";
    private static final String BUSINESS = "+16185550400";
    private static final String INTENT = "ACCEPT_SLOT";

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
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), ResponderConfig.class).block();
        mongo.remove(new Query(), ConversationState.class).block();
        mongo.remove(new Query(), ReplyLogEntry.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), RescheduleFillLog.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.getCollection("waitlist_slot_claims")
                .flatMap(c -> Mono.from(c.deleteMany(new org.bson.Document()))).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        mongo.save(Tenant.builder().id(tenantId).slug("reschedule-yes-it-" + tenantId)
                .displayName("RescheduleFlow YES IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "waitlist", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedAnthropic();
        seedResponderConfig();
    }

    // ── inbound YES → classify ACCEPT_SLOT → claim → materialize the PHI-free Appointment ──

    @Test
    void inboundYes_classifiesAffirmative_claimsSlot_createsPhiFreeAppointment() {
        stubClassify(INTENT);
        UUID rita = seedContact("Rita", SENDER, Set.of());
        String slotKey = UUID.randomUUID().toString();
        Instant slotStart = Instant.now().plus(Duration.ofHours(3));
        seedOffer(slotKey, rita, SENDER, slotStart);

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, BUSINESS, "yes please!")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.HANDLED);

        // The freed slot was claimed → a real PHI-free Appointment was created for the patient.
        List<Appointment> appts = mongo.findAll(Appointment.class).collectList().block();
        assertThat(appts).hasSize(1);
        assertThat(appts.get(0).getContactId()).isEqualTo(rita);
        assertThat(appts.get(0).getVisitTypeBucket().name()).isEqualTo("OTHER");

        WaitlistOffer o = mongo.findAll(WaitlistOffer.class).collectList().block().get(0);
        assertThat(o.getStatus()).isEqualTo(WaitlistOffer.Status.CLAIMED);
        // The engine sent the confirmation SMS (the handler returned no router reply → no double-text).
        assertThat(sentSms).hasSize(1);
    }

    // ── opt-out honored upstream — an opted-out sender's YES is IGNORED, no claim ──

    @Test
    void inboundYes_fromOptedOutSender_isIgnored_noClaim() {
        stubClassify(INTENT);
        UUID rita = seedContact("Rita", SENDER, Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));
        seedOffer(UUID.randomUUID().toString(), rita, SENDER, Instant.now().plus(Duration.ofHours(3)));

        InboundIntentRouter.Outcome outcome = router.handle(tenantId, SENDER, BUSINESS, "yes")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // The router gates an opted-out sender to IGNORED before any handler runs — no claim, no Appointment.
        assertThat(outcome).isEqualTo(InboundIntentRouter.Outcome.IGNORED);
        assertThat(mongo.findAll(Appointment.class).collectList().block()).isEmpty();
        WaitlistOffer o = mongo.findAll(WaitlistOffer.class).collectList().block().get(0);
        assertThat(o.getStatus()).isEqualTo(WaitlistOffer.Status.OFFERED);
        assertThat(sentSms).isEmpty();
    }

    // ── helpers ──

    private void seedResponderConfig() {
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
                .vertical(RescheduleWaitlistIntentHandler.VERTICAL)
                .intents(List.of(new IntentDefinition(INTENT, "Patient accepts an offered open slot.")))
                .replyCapPerContactPerDay(8)
                .build()).block();
    }

    private UUID seedContact(String firstName, String phone, Set<String> tags) {
        return mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(tags)
                .build()).block().getId();
    }

    private void seedOffer(String slotKey, UUID contactId, String phone, Instant slotStart) {
        mongo.save(WaitlistOffer.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .slotKey(slotKey).slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                .contactId(contactId).contactPhone(phone)
                .slotStart(slotStart).slotEnd(slotStart.plus(Duration.ofMinutes(30))).durationMinutes(30)
                .rank(0)
                .status(WaitlistOffer.Status.OFFERED)
                .sentAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build()).block();
    }

    private void seedAnthropic() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubClassify(String intent) {
        String raw = "{\"intent\":\"" + intent + "\",\"confidence\":0.96,\"extractedSlots\":{}}";
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
                + "\"usage\":{\"input_tokens\":40,\"output_tokens\":10}}";
    }
}
