package com.kumouri.kmodigipresbe.integration.twilio.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.integration.TwilioVoicemailEvent;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FD-3 — HealthFrontDeskVoicemailIT: the PHI-free voicemail-to-callback headline ITs (FrontDesk IQ
 * flagship #4). Mirrors {@link HomeServicesVoicemailIT} structurally (inbound signed Twilio webhook,
 * WireMock Anthropic via {@code @DynamicPropertySource}, {@code @MockitoBean} SMS/email notify seams,
 * the deterministic signed-URL helper) but seeds a tenant whose Twilio
 * {@code config.voicemailVertical="health-frontdesk"} so the {@code HealthFrontDeskExtractionStrategy}
 * is selected.
 *
 * <h2>The marquee fence — F2 (raw transcript never persisted or indexed)</h2>
 * The release-blocking assertion: a transcript that mentions clinical detail ("blood pressure",
 * "Lisinopril", "dizzy") yields a callback {@code Activity(CALL, INBOUND)} whose
 * <strong>{@code body} is the fixed redaction marker (NOT the transcript)</strong>, whose payload
 * carries NO recording pointer and NO transcript, and whose entire persisted form (body + payload)
 * contains <strong>none</strong> of the clinical tokens — only the logistics fields (name / callback
 * number / {@code intentBucket=PRESCRIPTION_REFILL_REQUEST}). PHI never lands anywhere.
 *
 * <p>Byte-equivalence of the mole ({@link TwilioVoicemailIT}) and multi-trade
 * ({@link HomeServicesVoicemailIT} / {@link HomeServicesVoicemailFieldServiceDisabledIT}) verticals —
 * which do NOT override {@code persistTranscript()} (default {@code true}) — is the standing constraint
 * those ITs guard; this class asserts the {@code false} branch.
 *
 * <p>§7: Anthropic → WireMock; Twilio SMS + email → {@code @MockitoBean}; sandbox keys; no live
 * call/charge/send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.files.region=us-east-1",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class HealthFrontDeskVoicemailIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_fd3";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-fd3-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String BASE_PATH = "";
    private static final String CALLER = "+16185550142";
    private static final String BUSINESS_NUMBER = "+16185550100";
    private static final String NOTIFY_EMAIL = "frontdesk@cedar-ridge-dental.test";
    private static final String NOTIFY_PHONE = "+16185550111";

    /**
     * The after-hours voicemail transcript. It deliberately contains distinctive CLINICAL tokens —
     * "blood pressure", "Lisinopril" (a drug name), "dizzy" (a symptom) — none of which appear in any
     * logistics-bucket vocabulary, so they are a sound forbidden-token set for the F2 assertion.
     */
    private static final String CLINICAL_TRANSCRIPT =
            "Hi, this is Dana Reyes, I need to get my blood pressure prescription Lisinopril refilled, "
                    + "I've been feeling dizzy. Please call me back at 555-0142. Thanks.";

    /** Clinical tokens that must NEVER appear in the persisted callback Activity (fence F2). */
    private static final List<String> FORBIDDEN_CLINICAL_TOKENS =
            List.of("blood pressure", "Lisinopril", "dizzy");

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
    @Autowired ObjectMapper objectMapper;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final List<String> smsBody = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), TwilioVoicemailEvent.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
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
                .id(tenantId).slug("fd3-voicemail-it-" + tenantId)
                .displayName("Cedar Ridge Dental IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build())
                .block();

        // Twilio connection — voicemailVertical="health-frontdesk" selects the health strategy.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_fd3",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", BUSINESS_NUMBER)))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL,
                        "notifyPhone", NOTIFY_PHONE,
                        "voicemailVertical", "health-frontdesk")))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    @AfterEach
    void cleanup() {
        // no-op (no event subscription held)
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
        form.add("TranscriptionText", CLINICAL_TRANSCRIPT);
        form.add("TranscriptionStatus", "completed");
        return form;
    }

    /**
     * WireMock stub returning a LOGISTICS-ONLY extraction (what the F2-guardrail prompt elicits): the
     * intent bucket + callback fields, with NO clinical detail echoed. The model never returns the drug
     * name / symptom; the strategy could not store it even if it did (the carrier has no clinical slot).
     */
    private void stubHealthLogistics() {
        String json = "{\\\"name\\\":\\\"Dana Reyes\\\","
                + "\\\"callbackNumber\\\":\\\"555-0142\\\","
                + "\\\"intentBucket\\\":\\\"PRESCRIPTION_REFILL_REQUEST\\\","
                + "\\\"callbackRequested\\\":true}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + json + "\"}],"
                                + "\"usage\":{\"input_tokens\":150,\"output_tokens\":40}}")));
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

    /** Serializes the whole Activity (body + payload) so the F2 forbidden-token scan is exhaustive. */
    private String serialize(Activity a) {
        try {
            return objectMapper.writeValueAsString(a);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Case 1 (THE MARQUEE / release-blocking F2 test) — clinical voicemail → a PHI-free callback
    // Activity: logistics-only extraction, NO raw transcript stored, NO clinical token anywhere.
    // -------------------------------------------------------------------------

    @Test
    void clinicalVoicemail_createsCallbackActivity_withNoTranscriptAndNoClinicalToken() {
        stubHealthLogistics();
        String callSid = "CA_fd3_phi_" + UUID.randomUUID();
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

        // Exactly one callback Activity(CALL, INBOUND) — logistics-only, transcript-free (F2).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).as("exactly one callback Activity").hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("CALL");
            assertThat(a.getDirection().name()).isEqualTo("INBOUND");

            // F2 — the raw transcript is NOT the body; the fixed redaction marker is.
            assertThat(a.getBody())
                    .as("body is the redaction marker, never the transcript")
                    .isEqualTo("(voicemail transcript not retained — front-desk callback)");
            assertThat(a.getBody()).doesNotContain("Lisinopril");

            // The logistics-only extraction survived on the payload.
            @SuppressWarnings("unchecked")
            Map<String, Object> extracted = (Map<String, Object>) a.getPayload().get("extractedJson");
            assertThat(extracted).isNotNull();
            assertThat(extracted.get("intentBucket")).isEqualTo("PRESCRIPTION_REFILL_REQUEST");
            assertThat(extracted.get("callbackNumber")).isEqualTo("555-0142");
            assertThat(extracted.get("callbackRequested")).isEqualTo(true);
            assertThat(extracted).as("no transcript echoed into extractedJson")
                    .doesNotContainKeys("transcript", "problem", "symptom");

            // F2 — the recording pointer (a path back to the spoken words) is omitted.
            assertThat(a.getPayload())
                    .as("recording SID/URL omitted for the PHI-sensitive vertical")
                    .doesNotContainKeys("recordingSid", "recordingUrl");

            // F2 (the headline) — NONE of the clinical tokens appear ANYWHERE in the stored Activity.
            String serialized = serialize(a);
            for (String token : FORBIDDEN_CLINICAL_TOKENS) {
                assertThat(serialized.toLowerCase())
                        .as("clinical token '%s' must never be persisted (fence F2)", token)
                        .doesNotContain(token.toLowerCase());
            }
        });

        // The Anthropic transport was called exactly once (logistics extraction happened).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        // Best-effort notify still fired (front desk learns of the callback) — but PHI-free: the
        // summary line names only the logistics bucket, never a clinical token.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(smsTo).contains(NOTIFY_PHONE);
            for (String token : FORBIDDEN_CLINICAL_TOKENS) {
                assertThat(String.join(" ", smsBody).toLowerCase())
                        .as("clinical token '%s' must never be sent outbound (fence F2/F3)", token)
                        .doesNotContain(token.toLowerCase());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Case 2 — best-effort on a Claude failure: still a callback Activity, still transcript-free (F2).
    // -------------------------------------------------------------------------

    @Test
    void extractionUpstreamFailure_stillCreatesCallbackActivity_transcriptStillRedacted() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));
        String callSid = "CA_fd3_fail_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        // A failed extraction must NOT drop the callback — a logistics-only callback Activity is still
        // created (caller-ID name), and the transcript is STILL redacted (F2 holds on the degraded path).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).as("one callback Activity even on extraction failure").hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("CALL");
            assertThat(a.getBody())
                    .as("transcript still redacted on the degraded path")
                    .isEqualTo("(voicemail transcript not retained — front-desk callback)");

            String serialized = serialize(a);
            for (String token : FORBIDDEN_CLINICAL_TOKENS) {
                assertThat(serialized.toLowerCase())
                        .as("clinical token '%s' must never be persisted even on failure", token)
                        .doesNotContain(token.toLowerCase());
            }
        });

        // Contact still created.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(Contact.class).collectList().block()).hasSize(1));
    }
}
