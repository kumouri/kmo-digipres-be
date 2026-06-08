package com.kumouri.kmodigipresbe.integration.twilio.voice;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.integration.TwilioVoicemailEvent;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HS-1 — HomeServicesVoicemailIT: the multi-trade voicemail → DRAFT WorkOrder headline ITs.
 * Mirrors {@link TwilioVoicemailIT} structurally (inbound signed Twilio webhook, WireMock Anthropic
 * via {@code @DynamicPropertySource}, {@code @MockitoBean} SMS/email notify seams, the deterministic
 * signed-URL helper) but seeds a tenant whose Twilio {@code config.voicemailVertical="home-services"}
 * and enables both the {@code home-services} and {@code field-service} modules so a
 * {@code WorkOrderService} bean is present (plan §4.8).
 *
 * <h2>Cases (plan §4.8)</h2>
 * <ol>
 *   <li>happy path: multi-trade JSON ({@code trade:HVAC, urgency:URGENT}) → 1 Contact, 1
 *       Activity(CALL,INBOUND) with the symptom in the body + {@code extractedJson.trade=HVAC}, 1
 *       DRAFT WorkOrder ({@code serviceType=HVAC}, {@code customFields.urgency=URGENT}, a
 *       server-assigned {@code workOrderNumber}), ledger {@code createdWorkOrderId} set, notify +
 *       auto-ack, {@code VOICEMAIL_WORK_ORDER_DRAFTED} + {@code VOICEMAIL_LEAD_CREATED}, and
 *       {@code verify(1, ...)};</li>
 *   <li>EMERGENCY recorded → WO {@code customFields.urgency=EMERGENCY};</li>
 *   <li>duplicate CallSid → 200 no-op, exactly one WO/Activity/ledger;</li>
 *   <li>best-effort extraction failure (WireMock 500) → still one DRAFT WO, {@code serviceType=GENERAL};</li>
 * </ol>
 * The {@code field-service-disabled degrade} case (4 in the plan) lives in
 * {@link HomeServicesVoicemailFieldServiceDisabledIT} (a separate class — {@code @TestPropertySource}
 * is class-level), proving the {@code ObjectProvider<WorkOrderService>} guard: Contact+Activity+notify
 * created, ZERO WorkOrder, no error, 200.
 *
 * <p>§7: Anthropic → WireMock; Twilio SMS + email → {@code @MockitoBean}; sandbox keys; no live
 * call/charge/send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.files.region=us-east-1",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class HomeServicesVoicemailIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_hs1";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-hs1-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String BASE_PATH = "";
    private static final String CALLER = "+16185550199";
    private static final String BUSINESS_NUMBER = "+16185550100";
    private static final String NOTIFY_EMAIL = "owner@hvac.test";
    private static final String NOTIFY_PHONE = "+16185550111";

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

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final List<String> smsBody = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), TwilioVoicemailEvent.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), WorkOrder.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();
        smsBody.clear();
        emailTo.set(null);

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            smsBody.add(req.body());
            return Mono.just(true);
        });
        when(emailService.sendSingleEmail(any(SingleEmailCommunicationRequest.class))).thenAnswer(inv -> {
            SingleEmailCommunicationRequest req = inv.getArgument(0);
            emailTo.set(req.to() == null ? null : req.to().asString());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("hs-voicemail-it-" + tenantId)
                .displayName("Home Services IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build())
                .block();

        // Twilio connection — voicemailVertical="home-services" selects the multi-trade strategy.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_hs1",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", BUSINESS_NUMBER)))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL,
                        "notifyPhone", NOTIFY_PHONE,
                        "voicemailVertical", "home-services")))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    // -------------------------------------------------------------------------
    // Twilio signing helper — mirrors TwilioRequestValidator exactly.
    // -------------------------------------------------------------------------

    private String fullUrl(String path) {
        return "https://" + FORWARDED_HOST + BASE_PATH + path;
    }

    private String sign(String fullUrl, MultiValueMap<String, String> form) {
        StringBuilder sb = new StringBuilder(fullUrl);
        TreeMap<String, String> sorted = new TreeMap<>();
        form.forEach((k, v) -> sorted.put(k, v.isEmpty() ? "" : v.get(0)));
        sorted.forEach((k, v) -> sb.append(k).append(v));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(AUTH_TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException("HMAC-SHA1 failed", ex);
        }
    }

    private MultiValueMap<String, String> voicemailForm(String callSid) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("CallSid", callSid);
        form.add("RecordingSid", "RE_test_" + callSid);
        form.add("RecordingUrl", "https://api.twilio.test/recordings/" + callSid);
        form.add("From", CALLER);
        form.add("To", BUSINESS_NUMBER);
        form.add("TranscriptionText",
                "Hi, this is Maria Lopez at 14 Oak Street, my furnace is making a loud banging and "
                        + "there is no heat, it is freezing, please call me back tonight.");
        form.add("TranscriptionStatus", "completed");
        return form;
    }

    private void stubMultiTrade(String urgency) {
        String json = "{\\\"name\\\":\\\"Maria Lopez\\\",\\\"phone\\\":null,"
                + "\\\"address\\\":\\\"14 Oak Street\\\",\\\"trade\\\":\\\"HVAC\\\","
                + "\\\"urgency\\\":\\\"" + urgency + "\\\",\\\"symptom\\\":\\\"no heat, loud banging\\\","
                + "\\\"jobValueBand\\\":\\\"MEDIUM\\\",\\\"callbackRequested\\\":true}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + json + "\"}],"
                                + "\"usage\":{\"input_tokens\":150,\"output_tokens\":50}}")));
    }

    private WebTestClient.ResponseSpec postVoicemail(String path, MultiValueMap<String, String> form,
                                                     String signature) {
        return web.post()
                .uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", signature != null ? signature : "")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange();
    }

    // -------------------------------------------------------------------------
    // Case 1 — happy path: multi-trade voicemail → Contact + Activity + DRAFT WorkOrder
    // -------------------------------------------------------------------------

    @Test
    void signedMultiTradeVoicemail_createsDraftWorkOrder_withTradeAndUrgency() {
        stubMultiTrade("URGENT");
        String callSid = "CA_hs_happy_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        // Exactly one Contact (find-or-create by caller phone).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Contact> all = mongo.findAll(Contact.class).collectList().block();
            assertThat(all).as("exactly one Contact").hasSize(1);
            assertThat(all.get(0).getPhones()).anyMatch(p -> CALLER.equals(p.number()));
        });

        // Exactly one Activity(CALL, INBOUND) with the symptom in the body + trade in payload.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).as("exactly one Activity").hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("CALL");
            assertThat(a.getDirection().name()).isEqualTo("INBOUND");
            assertThat(a.getBody()).contains("furnace");
            assertThat(a.getPayload()).containsKey("callSid");
            @SuppressWarnings("unchecked")
            Map<String, Object> extracted = (Map<String, Object>) a.getPayload().get("extractedJson");
            assertThat(extracted).isNotNull();
            assertThat(extracted.get("trade")).isEqualTo("HVAC");
        });

        // Exactly one DRAFT WorkOrder — serviceType=HVAC, customFields.urgency=URGENT, numbered.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<WorkOrder> wos = mongo.findAll(WorkOrder.class).collectList().block();
            assertThat(wos).as("exactly one DRAFT WorkOrder").hasSize(1);
            WorkOrder wo = wos.get(0);
            assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.DRAFT);
            assertThat(wo.getServiceType()).isEqualTo("HVAC");
            assertThat(wo.getWorkOrderNumber()).as("server-assigned number").isNotBlank();
            assertThat(wo.getTenantId()).isEqualTo(tenantId);
            assertThat(wo.getScheduledStart()).as("left null → off the dated board").isNull();
            assertThat(wo.getCustomFields()).containsEntry("urgency", "URGENT");
            assertThat(wo.getCustomFields()).containsEntry("callSid", callSid);
            assertThat(wo.getNotes()).contains("no heat");
        });

        // Ledger row carries the created WorkOrder id (+ contact/activity).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<TwilioVoicemailEvent> ledger =
                    mongo.findAll(TwilioVoicemailEvent.class).collectList().block();
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).getCallSid()).isEqualTo(callSid);
            assertThat(ledger.get(0).getResolvedContactId()).isNotNull();
            assertThat(ledger.get(0).getCreatedActivityId()).isNotNull();
            assertThat(ledger.get(0).getCreatedWorkOrderId()).as("createdWorkOrderId back-filled").isNotNull();
        });

        // Notify owner (email + SMS) + auto-ack the caller.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(smsTo).contains(NOTIFY_PHONE);
            assertThat(smsTo).contains(CALLER);
        });

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(observed).anyMatch(e -> DomainEventType.VOICEMAIL_LEAD_CREATED.equals(e.type()));
            assertThat(observed).anyMatch(e -> DomainEventType.VOICEMAIL_WORK_ORDER_DRAFTED.equals(e.type()));
        });
    }

    // -------------------------------------------------------------------------
    // Case 2 — EMERGENCY recorded: WO customFields.urgency=EMERGENCY (live-forward is HS-3)
    // -------------------------------------------------------------------------

    @Test
    void emergencyUrgency_recordedOnWorkOrder() {
        stubMultiTrade("EMERGENCY");
        String callSid = "CA_hs_emerg_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<WorkOrder> wos = mongo.findAll(WorkOrder.class).collectList().block();
            assertThat(wos).hasSize(1);
            WorkOrder wo = wos.get(0);
            assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.DRAFT);
            assertThat(wo.getServiceType()).isEqualTo("HVAC");
            assertThat(wo.getCustomFields()).containsEntry("urgency", "EMERGENCY");
        });
    }

    // -------------------------------------------------------------------------
    // Case 3 — duplicate CallSid → 200 no-op, exactly one WO/Activity/ledger
    // -------------------------------------------------------------------------

    @Test
    void duplicateCallSid_200NoOp_oneWorkOrder() {
        stubMultiTrade("URGENT");
        String callSid = "CA_hs_dup_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(WorkOrder.class).collectList().block()).hasSize(1));

        // Re-deliver the SAME CallSid → 200 no-op.
        postVoicemail(path, form, sig).expectStatus().isOk();

        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        assertThat(mongo.findAll(TwilioVoicemailEvent.class).collectList().block())
                .as("exactly one ledger row after re-delivery").hasSize(1);
        assertThat(mongo.findAll(Activity.class).collectList().block())
                .as("exactly one Activity after re-delivery").hasSize(1);
        assertThat(mongo.findAll(WorkOrder.class).collectList().block())
                .as("exactly one WorkOrder after re-delivery").hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Case 5 — best-effort extraction failure (WireMock 500) → DRAFT WO serviceType=GENERAL
    // -------------------------------------------------------------------------

    @Test
    void extractionUpstreamFailure_stillDraftsGeneralWorkOrder() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));
        String callSid = "CA_hs_fail_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        // A failed extraction must NOT drop the lead — a GENERAL DRAFT WorkOrder is still created
        // from the raw transcript (the multi-trade "AI is triage" rule).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<WorkOrder> wos = mongo.findAll(WorkOrder.class).collectList().block();
            assertThat(wos).as("one DRAFT WorkOrder even on extraction failure").hasSize(1);
            WorkOrder wo = wos.get(0);
            assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.DRAFT);
            assertThat(wo.getServiceType()).isEqualTo("GENERAL");
            assertThat(wo.getNotes()).contains("furnace");
        });
        // Contact + Activity still created.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(mongo.findAll(Contact.class).collectList().block()).hasSize(1);
            assertThat(mongo.findAll(Activity.class).collectList().block()).hasSize(1);
        });
    }
}
