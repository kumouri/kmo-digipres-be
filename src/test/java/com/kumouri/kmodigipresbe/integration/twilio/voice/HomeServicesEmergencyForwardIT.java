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
import java.util.List;
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
 * HS-3 — HomeServicesEmergencyForwardIT: the EMERGENCY live-forward IVR + two-sided outbound ITs.
 * Mirrors {@link HomeServicesVoicemailIT} structurally (inbound signed Twilio webhook, WireMock
 * Anthropic via {@code @DynamicPropertySource}, {@code @MockitoBean} SMS/email seams, the
 * deterministic signed-URL helper, both {@code home-services} + {@code field-service} enabled so a
 * {@code WorkOrderService} bean is present).
 *
 * <h2>The HS-3 design (plan §3 / §6 timing)</h2>
 * AI urgency is only known <em>after</em> transcription, so the live-forward is a
 * <strong>deterministic IVR gate</strong> on the voice webhook, NOT AI-gated mid-call:
 * <ol>
 *   <li><strong>Voice webhook with {@code onCallPhone}</strong> → a {@code <Gather numDigits="1">}
 *       emergency-IVR TwiML ("press 1 for the on-call tech, otherwise leave a message").</li>
 *   <li><strong>The {@code /voice/gather} callback, {@code Digits=1}</strong> →
 *       {@code <Dial>onCallPhone</Dial>} (the live-forward — telephony stubbed, no real call).</li>
 *   <li><strong>The gather callback with no/other input</strong> → the greeting + {@code <Record>}
 *       voicemail TwiML (the caller still leaves a message).</li>
 *   <li><strong>Voice webhook with NO {@code onCallPhone}</strong> (NMM / default) → the existing
 *       greeting + {@code <Record>} TwiML <strong>BYTE-IDENTICAL</strong> to before (the gate).</li>
 *   <li><strong>Two-sided outbound after voicemail+triage:</strong> an EMERGENCY-urgency lead →
 *       the owner digest (email + SMS) is EMERGENCY-flagged AND the caller auto-ack carries the
 *       configured {@code bookingLinkUrl} ("Book your visit: ...").</li>
 * </ol>
 *
 * <h2>§7 / telephony stubbed</h2>
 * Anthropic → WireMock; Twilio SMS + email → {@code @MockitoBean}; sandbox keys; <strong>no real
 * call, SMS, or forward anywhere</strong> — every assertion is on the TwiML response structure and
 * the captured stubbed {@code TwilioSmsService}. Go-live needs a real Twilio number, a verified
 * on-call number, and an A2P 10DLC campaign for the caller-facing booking SMS (plan §6).
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
class HomeServicesEmergencyForwardIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_hs3";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-hs3-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String CALLER = "+16185550199";
    private static final String BUSINESS_NUMBER = "+16185550100";
    private static final String ON_CALL_PHONE = "+16185550123";
    private static final String NOTIFY_EMAIL = "owner@hvac.test";
    private static final String NOTIFY_PHONE = "+16185550111";
    private static final String BOOKING_LINK = "https://book.hvac.test/abc";

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

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final List<String> smsBody = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();
    private final AtomicReference<String> emailSubject = new AtomicReference<>();
    private final AtomicReference<String> emailBody = new AtomicReference<>();

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
        smsBody.clear();
        emailTo.set(null);
        emailSubject.set(null);
        emailBody.set(null);

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            smsBody.add(req.body());
            return Mono.just(true);
        });
        when(emailService.sendSingleEmail(any(SingleEmailCommunicationRequest.class))).thenAnswer(inv -> {
            SingleEmailCommunicationRequest req = inv.getArgument(0);
            emailTo.set(req.to() == null ? null : req.to().asString());
            emailSubject.set(req.subject());
            emailBody.set(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("hs3-it-" + tenantId)
                .displayName("HS-3 IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build())
                .block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    @AfterEach
    void cleanup() {
    }

    // -------------------------------------------------------------------------
    // Per-test Twilio connection seeding — vary the config (onCallPhone / bookingLinkUrl).
    // -------------------------------------------------------------------------

    private void seedTwilio(Map<String, String> extraConfig) {
        Map<String, String> config = new HashMap<>(Map.of(
                "notifyEmail", NOTIFY_EMAIL,
                "notifyPhone", NOTIFY_PHONE,
                "voicemailVertical", "home-services"));
        config.putAll(extraConfig);
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_hs3",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", BUSINESS_NUMBER)))
                .config(config)
                .build()).block();
    }

    /**
     * Captures the <em>actual</em> deployed greeting+record voice TwiML — the byte-for-byte
     * baseline the NMM gate protects — by seeding an ephemeral tenant with a Twilio connection that
     * has NO {@code onCallPhone} and POSTing its {@code /voice} webhook. Config-independent: whatever
     * the deployed {@code kmosf.voicemail.greeting} / record length is, this returns the exact bytes
     * {@code TwilioVoicemailService#buildVoiceTwiml} produces, so the gather fall-through + the
     * no-gate assertions compare against the real thing rather than a brittle hard-coded literal.
     */
    private String baselineRecordTwiml() {
        UUID baselineTenant = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(baselineTenant).slug("hs3-baseline-" + baselineTenant)
                .displayName("HS-3 baseline").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(baselineTenant)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_hs3_base",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", BUSINESS_NUMBER)))
                .config(new HashMap<>(Map.of("voicemailVertical", "home-services")))
                .build()).block();
        String path = "/public/integrations/twilio/" + baselineTenant + "/voice";
        MultiValueMap<String, String> form = voiceForm();
        String sig = sign(fullUrl(path), form);
        return postTwiml(path, form, sig);
    }

    // -------------------------------------------------------------------------
    // Twilio signing helper — mirrors TwilioRequestValidator exactly.
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

    private MultiValueMap<String, String> voiceForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("CallSid", "CA_voice_" + UUID.randomUUID());
        form.add("From", CALLER);
        form.add("To", BUSINESS_NUMBER);
        return form;
    }

    private MultiValueMap<String, String> gatherForm(String digits) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("CallSid", "CA_gather_" + UUID.randomUUID());
        form.add("From", CALLER);
        form.add("To", BUSINESS_NUMBER);
        if (digits != null) {
            form.add("Digits", digits);
        }
        return form;
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

    private String postTwiml(String path, MultiValueMap<String, String> form, String signature) {
        return web.post()
                .uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", signature != null ? signature : "")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_XML)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
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
    // (a) Voice webhook with onCallPhone → emergency <Gather> IVR TwiML
    // -------------------------------------------------------------------------

    @Test
    void voiceWebhook_withOnCallPhone_returnsEmergencyGatherTwiml() {
        seedTwilio(Map.of("onCallPhone", ON_CALL_PHONE));
        String path = "/public/integrations/twilio/" + tenantId + "/voice";
        MultiValueMap<String, String> form = voiceForm();
        String sig = sign(fullUrl(path), form);

        String body = postTwiml(path, form, sig);

        assertThat(body).as("emergency IVR Gather").contains("<Gather numDigits=\"1\"");
        assertThat(body).contains("action=\"voice/gather\"");
        assertThat(body).contains("press 1");
        // Falls through to the record-voicemail TwiML on no input (so the caller still leaves a msg).
        assertThat(body).contains("transcribe=\"true\"");
        assertThat(body).contains("transcribeCallback=\"voicemail\"");
        // It must NOT live-forward here — the Dial only happens after the digit on /voice/gather.
        assertThat(body).doesNotContain("<Dial>");
    }

    // -------------------------------------------------------------------------
    // (b) Gather callback with Digits=1 → <Dial>onCallPhone</Dial>
    // -------------------------------------------------------------------------

    @Test
    void gatherCallback_digit1_returnsDialOnCallPhone() {
        seedTwilio(Map.of("onCallPhone", ON_CALL_PHONE));
        String path = "/public/integrations/twilio/" + tenantId + "/voice/gather";
        MultiValueMap<String, String> form = gatherForm("1");
        String sig = sign(fullUrl(path), form);

        String body = postTwiml(path, form, sig);

        assertThat(body).as("live-forward to the on-call tech")
                .contains("<Dial>" + ON_CALL_PHONE + "</Dial>");
        assertThat(body).doesNotContain("<Record");
        assertThat(body).doesNotContain("<Gather");
    }

    // -------------------------------------------------------------------------
    // (c) Gather callback with no/other input → the record-voicemail TwiML
    // -------------------------------------------------------------------------

    @Test
    void gatherCallback_noDigit_fallsThroughToRecordVoicemailTwiml() {
        String baseline = baselineRecordTwiml();
        seedTwilio(Map.of("onCallPhone", ON_CALL_PHONE));
        String path = "/public/integrations/twilio/" + tenantId + "/voice/gather";
        MultiValueMap<String, String> form = gatherForm(null); // no Digits → timeout/no-input
        String sig = sign(fullUrl(path), form);

        String body = postTwiml(path, form, sig);

        assertThat(body).as("no input falls through to the exact record-voicemail TwiML")
                .isEqualTo(baseline);
    }

    @Test
    void gatherCallback_otherDigit_fallsThroughToRecordVoicemailTwiml() {
        String baseline = baselineRecordTwiml();
        seedTwilio(Map.of("onCallPhone", ON_CALL_PHONE));
        String path = "/public/integrations/twilio/" + tenantId + "/voice/gather";
        MultiValueMap<String, String> form = gatherForm("9"); // not "1"
        String sig = sign(fullUrl(path), form);

        String body = postTwiml(path, form, sig);

        assertThat(body).as("a non-1 digit falls through to the exact record-voicemail TwiML")
                .isEqualTo(baseline);
    }

    // -------------------------------------------------------------------------
    // (d) NO onCallPhone → handleVoice TwiML BYTE-IDENTICAL to the existing greeting+record
    // -------------------------------------------------------------------------

    @Test
    void voiceWebhook_withoutOnCallPhone_returnsExistingRecordTwiml_byteIdentical() {
        // The baseline is itself a no-onCallPhone /voice response; assert this one matches it AND is a
        // pure greeting+record TwiML (no Gather/Dial) — the byte-equivalence gate that proves HS-3
        // does not change the NMM voice flow when onCallPhone is unset.
        String baseline = baselineRecordTwiml();
        seedTwilio(Map.of()); // no onCallPhone → the NMM gate
        String path = "/public/integrations/twilio/" + tenantId + "/voice";
        MultiValueMap<String, String> form = voiceForm();
        String sig = sign(fullUrl(path), form);

        String body = postTwiml(path, form, sig);

        assertThat(body).as("no-onCallPhone voice TwiML is byte-identical to the greeting+record")
                .isEqualTo(baseline);
        assertThat(body).contains("transcribe=\"true\"");
        assertThat(body).contains("transcribeCallback=\"voicemail\"");
        assertThat(body).doesNotContain("<Gather");
        assertThat(body).doesNotContain("<Dial");
    }

    // -------------------------------------------------------------------------
    // (e) EMERGENCY lead → owner EMERGENCY-flagged digest + caller booking-link SMS
    // -------------------------------------------------------------------------

    @Test
    void emergencyVoicemail_flagsOwnerDigest_andSendsCallerBookingLink() {
        seedTwilio(Map.of("onCallPhone", ON_CALL_PHONE, "bookingLinkUrl", BOOKING_LINK));
        stubMultiTrade("EMERGENCY");
        String callSid = "CA_hs3_emerg_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        // Owner digest is EMERGENCY-flagged (email subject + body + SMS) and the caller booking-link
        // SMS is sent. (The WorkOrder customFields.urgency=EMERGENCY is covered by HomeServicesVoicemailIT.)
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(emailSubject.get()).as("owner email subject EMERGENCY-flagged").startsWith("EMERGENCY:");
            assertThat(emailBody.get()).as("owner email body EMERGENCY banner").contains("EMERGENCY");

            // Owner SMS (to notifyPhone) is EMERGENCY-prefixed.
            int ownerIdx = smsTo.indexOf(NOTIFY_PHONE);
            assertThat(ownerIdx).as("owner SMS dispatched").isGreaterThanOrEqualTo(0);
            assertThat(smsBody.get(ownerIdx)).as("owner SMS EMERGENCY-prefixed").startsWith("EMERGENCY:");

            // Caller auto-ack (to CALLER) carries the booking link.
            int callerIdx = smsTo.indexOf(CALLER);
            assertThat(callerIdx).as("caller auto-ack dispatched").isGreaterThanOrEqualTo(0);
            assertThat(smsBody.get(callerIdx)).as("caller booking link").contains(BOOKING_LINK);
            assertThat(smsBody.get(callerIdx)).contains("Book your visit:");
        });
    }

    // -------------------------------------------------------------------------
    // (e-gate) NON-emergency, no booking link → owner digest + caller auto-ack byte-unchanged
    // -------------------------------------------------------------------------

    @Test
    void urgentVoicemail_noBookingConfig_ownerDigestAndAutoAckNotFlagged() {
        // home-services tenant but URGENT (not EMERGENCY) and NO bookingLinkUrl configured.
        seedTwilio(Map.of()); // no onCallPhone, no bookingLinkUrl
        stubMultiTrade("URGENT");
        String callSid = "CA_hs3_urgent_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailSubject.get()).as("non-emergency subject NOT flagged").isNotNull();
            assertThat(emailSubject.get()).doesNotStartWith("EMERGENCY:");
            assertThat(emailBody.get()).doesNotContain("<strong>EMERGENCY</strong>");

            int ownerIdx = smsTo.indexOf(NOTIFY_PHONE);
            assertThat(ownerIdx).isGreaterThanOrEqualTo(0);
            assertThat(smsBody.get(ownerIdx)).doesNotStartWith("EMERGENCY:");

            // Caller auto-ack carries NO booking link (none configured) — bare auto-ack message.
            int callerIdx = smsTo.indexOf(CALLER);
            assertThat(callerIdx).isGreaterThanOrEqualTo(0);
            assertThat(smsBody.get(callerIdx)).doesNotContain("Book your visit:");
        });
    }
}
