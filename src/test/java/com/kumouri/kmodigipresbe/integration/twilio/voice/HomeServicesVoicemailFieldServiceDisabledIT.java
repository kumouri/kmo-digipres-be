package com.kumouri.kmodigipresbe.integration.twilio.voice;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.integration.TwilioVoicemailEvent;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
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
import org.springframework.context.ApplicationContext;
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
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HS-1 plan §4.8 case 4 — the field-service-disabled degrade. A separate class from
 * {@link HomeServicesVoicemailIT} because {@code @TestPropertySource} is class-level: here
 * {@code home-services} is enabled but {@code field-service} is <strong>disabled</strong>, so the
 * {@code WorkOrderService} bean is absent and {@code TwilioVoicemailService}'s
 * {@code ObjectProvider<WorkOrderService>} guard fires.
 *
 * <p>A home-services-vertical voicemail therefore degrades to Contact + Activity(CALL,INBOUND) +
 * notify — <strong>ZERO WorkOrder, no error, 200</strong> (plan §4.4) — proving the voicemail
 * module still boots and serves on a server with field-service off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=false",
        "kmosf.files.region=us-east-1",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class HomeServicesVoicemailFieldServiceDisabledIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_hs1_nofs";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-hs1-nofs-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
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
    @Autowired ApplicationContext appCtx;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final java.util.List<String> smsTo = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;

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
        emailTo.set(null);

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            return Mono.just(true);
        });
        when(emailService.sendSingleEmail(any(SingleEmailCommunicationRequest.class))).thenAnswer(inv -> {
            SingleEmailCommunicationRequest req = inv.getArgument(0);
            emailTo.set(req.to() == null ? null : req.to().asString());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("hs-nofs-it-" + tenantId)
                .displayName("HS-no-FS IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_hs1_nofs",
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
    }

    @AfterEach
    void noop() {
    }

    @Test
    void fieldServiceDisabled_workOrderServiceBeanAbsent() {
        // Precondition for the degrade: no WorkOrderService bean on this server.
        assertThat(appCtx.getBeanNamesForType(
                com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService.class))
                .as("WorkOrderService bean is absent when field-service is disabled")
                .isEmpty();
    }

    @Test
    void homeServicesVoicemail_fieldServiceDisabled_degradesToContactActivityNotify_zeroWorkOrder() {
        stubMultiTrade();
        String callSid = "CA_hs_nofs_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        // 200 — the degrade is silent (no error surfaced to Twilio).
        postVoicemail(path, form, sig).expectStatus().isOk();

        // Contact + Activity + ledger + notify still happen.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(mongo.findAll(Contact.class).collectList().block()).hasSize(1);
            assertThat(mongo.findAll(Activity.class).collectList().block()).hasSize(1);
            assertThat(mongo.findAll(TwilioVoicemailEvent.class).collectList().block()).hasSize(1);
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(smsTo).contains(NOTIFY_PHONE);
        });

        // ZERO WorkOrder (the ObjectProvider guard degraded — no field-service).
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(mongo.findAll(WorkOrder.class).collectList().block())
                .as("no WorkOrder when field-service is disabled").isEmpty();
        // Ledger createdWorkOrderId stays null.
        assertThat(mongo.findAll(TwilioVoicemailEvent.class).collectList().block().get(0)
                .getCreatedWorkOrderId())
                .as("ledger createdWorkOrderId null on degrade").isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers (mirror HomeServicesVoicemailIT).
    // -------------------------------------------------------------------------

    private String fullUrl(String path) {
        return "https://" + FORWARDED_HOST + path;
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
        form.add("From", CALLER);
        form.add("To", BUSINESS_NUMBER);
        form.add("TranscriptionText",
                "Hi, this is Maria Lopez, my furnace has no heat, please call me back.");
        form.add("TranscriptionStatus", "completed");
        return form;
    }

    private void stubMultiTrade() {
        String json = "{\\\"name\\\":\\\"Maria Lopez\\\",\\\"trade\\\":\\\"HVAC\\\","
                + "\\\"urgency\\\":\\\"URGENT\\\",\\\"symptom\\\":\\\"no heat\\\","
                + "\\\"callbackRequested\\\":true}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + json + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":40}}")));
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
}
