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
 * Phase 1 — TwilioVoicemailIT: the voicemail-to-lead headline ITs. Mirrors
 * {@code CalComWebhookIT} / {@code StripeWebhookIdempotencyIT} / {@code DocumensoWebhookSignedIT}
 * (inbound signed webhook, DB-side + DomainEvent-stream assertions).
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic extraction → WireMock ({@code kmosf.ai.anthropic.base-url} via
 * {@code @DynamicPropertySource}); Twilio SMS + email notify seams → {@code @MockitoBean}
 * (the {@code OnTheWayDispatchIT} precedent — {@code TwilioSmsService}'s base URL is not
 * config-driven, so the seam is mocked rather than standing up a fake Twilio host; this also
 * verifies the dispatch contract precisely). The Twilio {@code authToken} + Anthropic
 * {@code apiKey} are sandbox fakes. No live call / charge / send anywhere.
 *
 * <h2>Deterministic signed URL</h2>
 * The request carries {@code X-Forwarded-Proto: https} + {@code X-Forwarded-Host} so the
 * service's {@code reconstructFullUrl} produces a fixed public URL independent of the random
 * server port — exactly the production Cloudflare/Caddy scenario. The test computes the Twilio
 * signature over that same URL + the sorted form params.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>valid signed transcription callback → Contact + Activity(CALL,INBOUND) created +
 *       notify (email + SMS) + auto-ack SMS dispatched + one ledger row + VOICEMAIL_* events;</li>
 *   <li>invalid X-Twilio-Signature → 401 (4000), zero effect, zero WireMock + zero notify;</li>
 *   <li>duplicate CallSid redelivery → 200 no-op, exactly one Activity/ledger row (§9 #1);</li>
 *   <li>not-connected tenant → 4001/404, zero effect;</li>
 *   <li>TwiML voice endpoint → signed → returns the expected &lt;Record transcribe.../&gt; XML.</li>
 * </ul>
 *
 * <p>No functional shard splinter beyond the precedented notify mocks + the one WireMock
 * base-URL {@code @DynamicPropertySource}; self-clean {@code mongo.remove} {@code @BeforeEach}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class TwilioVoicemailIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_phase1";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-phase1-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String BASE_PATH = "";
    private static final String CALLER = "+16185550199";
    private static final String BUSINESS_NUMBER = "+16185550100";
    private static final String NOTIFY_EMAIL = "rob@nomomole.test";
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
        // §7 boundary: the voicemail-extraction Anthropic call goes to WireMock, never a real host.
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    // §7: the Twilio SMS + email notify seams are mocked (no live send) — the OnTheWayDispatchIT
    // precedent. We capture the dispatched SMS/email to assert notify + auto-ack happened.
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
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();
        smsBody.clear();
        emailTo.set(null);

        // Mock the SMS seam — capture and succeed.
        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            smsBody.add(req.body());
            return Mono.just(true);
        });
        // Mock the email seam — capture and succeed.
        when(emailService.sendSingleEmail(any(SingleEmailCommunicationRequest.class))).thenAnswer(inv -> {
            SingleEmailCommunicationRequest req = inv.getArgument(0);
            emailTo.set(req.to() == null ? null : req.to().asString());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("voicemail-it-" + tenantId)
                .displayName("Voicemail IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))   // non-zero so the extraction budget gate passes
                .build())
                .block();

        // Twilio connection — authToken for signature verify, fromNumber/accountSid for the
        // (mocked) outbound SMS; config carries the per-tenant notify targets (NOT hardcoded).
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_phase1",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", BUSINESS_NUMBER)))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL,
                        "notifyPhone", NOTIFY_PHONE)))
                .build()).block();

        // Anthropic connection — sandbox apiKey so the extraction service resolves a key.
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
                "Hi, this is Jane Doe at 123 Oak Street. I have moles tearing up my back yard, "
                        + "it is pretty bad, please call me back as soon as you can.");
        form.add("TranscriptionStatus", "completed");
        return form;
    }

    private void stubAnthropicExtraction() {
        // The extraction service POSTs to the WireMock root (base-url is the full messages URL;
        // uri("") → the configured base path "/"). Return a valid Anthropic Messages response
        // whose text block is the strict JSON the prompt asks for.
        String json = "{\\\"name\\\":\\\"Jane Doe\\\",\\\"phone\\\":null,"
                + "\\\"address\\\":\\\"123 Oak Street\\\",\\\"problem\\\":\\\"moles in the back yard\\\","
                + "\\\"urgency\\\":\\\"high\\\",\\\"callbackRequested\\\":true}";
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

    // -------------------------------------------------------------------------
    // Happy path: valid signed callback → Contact + Activity + notify + auto-ack
    // -------------------------------------------------------------------------

    @Test
    void signedVoicemail_createsContactAndActivity_dispatchesNotifyAndAutoAck() {
        stubAnthropicExtraction();
        String callSid = "CA_happy_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig).expectStatus().isOk();

        // Contact created (find-or-create by caller phone).
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Contact> all = mongo.findAll(Contact.class).collectList().block();
            assertThat(all).as("exactly one Contact created for the caller").hasSize(1);
            assertThat(all.get(0).getPhones()).anyMatch(p -> CALLER.equals(p.number()));
        });

        // Activity(CALL, INBOUND) created with the transcript as body.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).as("exactly one Activity created").hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("CALL");
            assertThat(a.getDirection().name()).isEqualTo("INBOUND");
            assertThat(a.getBody()).contains("moles");
            assertThat(a.getPayload()).containsKey("callSid");
        });

        // One ledger row.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<TwilioVoicemailEvent> ledger =
                    mongo.findAll(TwilioVoicemailEvent.class).collectList().block();
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).getCallSid()).isEqualTo(callSid);
            assertThat(ledger.get(0).getResolvedContactId()).isNotNull();
            assertThat(ledger.get(0).getCreatedActivityId()).isNotNull();
        });

        // Notify Rob (email to the per-tenant notifyEmail) + SMS, and auto-ack to the caller.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(smsTo).contains(NOTIFY_PHONE);   // notify-Rob SMS
            assertThat(smsTo).contains(CALLER);         // auto-ack to caller
        });

        // Anthropic extraction was called against WireMock (proves §7 base-url override + JSON parse).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        // Advisory events observed.
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(observed).anyMatch(e -> DomainEventType.VOICEMAIL_RECEIVED.equals(e.type()));
            assertThat(observed).anyMatch(e -> DomainEventType.VOICEMAIL_LEAD_CREATED.equals(e.type()));
        });
    }

    // -------------------------------------------------------------------------
    // Invalid signature → 401 (4000), zero effect, zero WireMock + zero notify
    // -------------------------------------------------------------------------

    @Test
    void invalidSignature_401_4000_zeroEffect() {
        stubAnthropicExtraction();
        String callSid = "CA_badsig_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);

        postVoicemail(path, form, "deadbeef_invalid_signature")
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4000);

        // Zero effect.
        assertThat(mongo.findAll(TwilioVoicemailEvent.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Contact.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Activity.class).collectList().block()).isEmpty();
        // Zero WireMock Anthropic traffic, zero notify.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        assertThat(smsTo).isEmpty();
        assertThat(emailTo.get()).isNull();
    }

    // -------------------------------------------------------------------------
    // Duplicate CallSid redelivery → 200 no-op, exactly one Activity/ledger row
    // -------------------------------------------------------------------------

    @Test
    void duplicateCallSid_200NoOp_zeroSecondEffect() {
        stubAnthropicExtraction();
        String callSid = "CA_dup_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + tenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        // First delivery.
        postVoicemail(path, form, sig).expectStatus().isOk();

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(Activity.class).collectList().block()).hasSize(1));

        // Re-deliver the SAME CallSid → 200 no-op.
        postVoicemail(path, form, sig).expectStatus().isOk();

        // Brief processing window — no second effect.
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        assertThat(mongo.findAll(TwilioVoicemailEvent.class).collectList().block())
                .as("exactly one ledger row after re-delivery").hasSize(1);
        assertThat(mongo.findAll(Activity.class).collectList().block())
                .as("exactly one Activity after re-delivery").hasSize(1);
        assertThat(mongo.findAll(Contact.class).collectList().block())
                .as("exactly one Contact after re-delivery").hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Not-connected tenant → 4001/404, zero effect
    // -------------------------------------------------------------------------

    @Test
    void notConnectedTenant_4001_zeroEffect() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("voicemail-notconn-" + otherTenantId)
                .displayName("Not-Connected Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        // No Twilio IntegrationConnection for otherTenantId.

        String callSid = "CA_notconn_" + UUID.randomUUID();
        String path = "/public/integrations/twilio/" + otherTenantId + "/voicemail";
        MultiValueMap<String, String> form = voicemailForm(callSid);
        String sig = sign(fullUrl(path), form);

        postVoicemail(path, form, sig)
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4001);

        assertThat(mongo.findAll(TwilioVoicemailEvent.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Activity.class).collectList().block()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // TwiML voice endpoint → signed → returns the expected <Record transcribe.../> XML
    // -------------------------------------------------------------------------

    @Test
    void voiceEndpoint_signed_returnsRecordTwiml() {
        String path = "/public/integrations/twilio/" + tenantId + "/voice";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("CallSid", "CA_voice_" + UUID.randomUUID());
        form.add("From", CALLER);
        form.add("To", BUSINESS_NUMBER);
        String sig = sign(fullUrl(path), form);

        String body = web.post()
                .uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_XML)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("<Response>");
        assertThat(body).contains("<Say>");
        assertThat(body).contains("transcribe=\"true\"");
        assertThat(body).contains("transcribeCallback=\"/public/integrations/twilio/"
                + tenantId + "/voicemail\"");
        assertThat(body).contains("playBeep=\"true\"");
    }

    @Test
    void voiceEndpoint_invalidSignature_401_4000() {
        String path = "/public/integrations/twilio/" + tenantId + "/voice";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("CallSid", "CA_voicebad_" + UUID.randomUUID());
        form.add("From", CALLER);

        web.post()
                .uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", "bad")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4000);
    }
}
