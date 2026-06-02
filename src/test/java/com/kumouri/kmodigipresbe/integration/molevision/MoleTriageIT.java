package com.kumouri.kmodigipresbe.integration.molevision;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.molevision.support.MoleTriageItStorageTestConfig;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Phase 2 — MoleTriageIT: the public photo-intake headline ITs. Mirrors {@code TwilioVoicemailIT}
 * (Phase 1) + {@code ServiceRequestWidgetIT} (the tokenized public-widget precedent).
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call → WireMock ({@code kmosf.ai.anthropic.base-url} via
 * {@code @DynamicPropertySource}); the Twilio SMS + email notify seams → {@code @MockitoBean}
 * (the {@code OnTheWayDispatchIT} / Phase-1 precedent — those base URLs are not config-driven, so
 * the seam is mocked rather than standing up a fake host; this also verifies the dispatch contract
 * precisely). {@link com.kumouri.kmodigipresbe.service.storage.FileStorageService} is the in-memory
 * {@link MoleTriageItStorageTestConfig} {@code @Bean @Primary} stub (the {@code ContractItStorageTestConfig}
 * precedent — exercises {@code putBytes} without a live bucket; NOT a {@code @MockBean} splinter).
 * The widget token is signed with the test {@code kmosf.security.widget-token-secret}; the
 * Anthropic {@code apiKey} is a sandbox fake. No live charge / send / upload anywhere.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>valid token + above-threshold mole photo → 200 + classification returned + Attachment
 *       (MOLE_PHOTO) stored + Contact + Activity(NOTE) created + notify (email + SMS) dispatched +
 *       MOLE_PHOTO_CLASSIFIED + MOLE_LEAD_CREATED events + the image content block hit WireMock;</li>
 *   <li>below-threshold "unsure" photo → 200 + aboveThreshold=false + "unclear" framing, still
 *       recorded (Attachment + Contact + Activity);</li>
 *   <li>wrong-widgetType token → 401/4010, zero effect;</li>
 *   <li>tampered token → 401 (1602), zero effect, zero WireMock;</li>
 *   <li>missing image part → 400/4011;</li>
 *   <li>unsupported media type → 415/4012.</li>
 * </ul>
 *
 * <p>Shard-safe: the only mocks are the two precedented notify seams + the WireMock base-URL
 * {@code @DynamicPropertySource}; storage is the precedented in-memory {@code @Bean} stub;
 * self-clean {@code mongo.remove} {@code @BeforeEach}; no {@code application-test.properties} /
 * {@code build.gradle} shard change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, MoleTriageItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=mole-triage-it-secret-0123456789",
        "kmosf.mole-triage.confidence-threshold=0.6"
})
class MoleTriageIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-phase2-fake";
    private static final String NOTIFY_EMAIL = "rob@nomomole.test";
    private static final String NOTIFY_PHONE = "+16185550111";
    private static final String CALLER_PHONE = "+16185550199";
    private static final byte[] FAKE_IMAGE = "fake-jpeg-photo-bytes".getBytes();

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
    @Autowired PublicWidgetTokenService tokens;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
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
                .id(tenantId).slug("mole-triage-it-" + tenantId)
                .displayName("Mole Triage IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        // Twilio connection — config carries the per-tenant notify targets (NOT hardcoded);
        // the SMS itself is mocked.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_phase2",
                        "authToken", "twilio_test_authtoken_phase2",
                        "fromNumber", "+16185550100")))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL,
                        "notifyPhone", NOTIFY_PHONE)))
                .build()).block();

        // Anthropic connection — sandbox apiKey so MoleVisionService resolves a key.
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

    private void stubVision(String classification, double confidence) {
        String inner = "{\\\"classification\\\":\\\"" + classification + "\\\",\\\"confidence\\\":"
                + confidence + ",\\\"rationale\\\":\\\"test rationale\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":30}}")));
    }

    private String issueToken(String widgetType) {
        return tokens.issue(tenantId, widgetType, Duration.ofHours(1));
    }

    private WebTestClient.ResponseSpec postPhoto(String token, byte[] image, String mediaType,
                                                 String filename, Map<String, String> textParts) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        if (image != null) {
            builder.part("image", new ByteArrayResource(image) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }, mediaType == null ? null : MediaType.parseMediaType(mediaType));
        }
        if (textParts != null) {
            textParts.forEach(builder::part);
        }
        return web.post()
                .uri("/public/integrations/mole-triage/" + token + "/classify")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    // -------------------------------------------------------------------------
    // Happy path: valid token + above-threshold mole → stored + lead + notify + events
    // -------------------------------------------------------------------------

    @Test
    void validToken_abovethresholdMole_storesLeadNotifiesReturnsClassification() {
        stubVision("mole", 0.92);
        String token = issueToken(MoleTriageService.WIDGET_TYPE);

        MoleTriageResponse resp = postPhoto(token, FAKE_IMAGE, "image/jpeg", "yard.jpg",
                Map.of("name", "Jane Doe", "phone", CALLER_PHONE, "address", "123 Oak Street"))
                .expectStatus().isOk()
                .expectBody(MoleTriageResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.classification()).isEqualTo("mole");
        assertThat(resp.confidence()).isEqualTo(0.92);
        assertThat(resp.aboveThreshold()).isTrue();
        assertThat(resp.message()).containsIgnoringCase("mole");
        assertThat(resp.attachmentId()).isNotNull();

        // Attachment(MOLE_PHOTO) stored under the tenant prefix.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Attachment> atts = mongo.findAll(Attachment.class).collectList().block();
            assertThat(atts).hasSize(1);
            assertThat(atts.get(0).getSubjectType()).isEqualTo("MOLE_PHOTO");
            assertThat(atts.get(0).getStorageRef()).startsWith("tenants/" + tenantId + "/");
            assertThat(atts.get(0).getContentType()).isEqualTo("image/jpeg");
        });

        // Contact found-or-created with the supplied phone.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Contact> all = mongo.findAll(Contact.class).collectList().block();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getPhones()).anyMatch(p -> CALLER_PHONE.equals(p.number()));
            assertThat(all.get(0).getTags()).contains("mole-triage-lead");
        });

        // Activity(NOTE) created with the classification summary + payload.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("NOTE");
            assertThat(a.getSubjectType().name()).isEqualTo("CONTACT");
            assertThat(a.getSummary()).containsIgnoringCase("mole");
            assertThat(a.getPayload()).containsKey("classification");
            assertThat(a.getPayload()).containsKey("attachmentId");
        });

        // Notify Rob (email + SMS to the per-tenant config targets).
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(smsTo).contains(NOTIFY_PHONE);
        });

        // The vision call actually went to WireMock with an image content block (§7).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        // Advisory events observed.
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(observed).anyMatch(e -> DomainEventType.MOLE_PHOTO_CLASSIFIED.equals(e.type()));
            assertThat(observed).anyMatch(e -> DomainEventType.MOLE_LEAD_CREATED.equals(e.type()));
        });
    }

    // -------------------------------------------------------------------------
    // Below-threshold "unsure" → 200, recorded, aboveThreshold=false, "unclear" framing
    // -------------------------------------------------------------------------

    @Test
    void belowThresholdUnsure_recordedButFramedUnclear() {
        stubVision("unsure", 0.20);
        String token = issueToken(MoleTriageService.WIDGET_TYPE);

        MoleTriageResponse resp = postPhoto(token, FAKE_IMAGE, "image/jpeg", "blurry.jpg",
                Map.of("phone", CALLER_PHONE))
                .expectStatus().isOk()
                .expectBody(MoleTriageResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.classification()).isEqualTo("unsure");
        assertThat(resp.aboveThreshold()).isFalse();
        assertThat(resp.message()).containsIgnoringCase("unclear");

        // Still recorded — Attachment + Contact + Activity all created.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);
            assertThat(mongo.findAll(Contact.class).collectList().block()).hasSize(1);
            assertThat(mongo.findAll(Activity.class).collectList().block()).hasSize(1);
        });
    }

    // -------------------------------------------------------------------------
    // Below-threshold but a confident-enough mole is NOT above threshold either
    // -------------------------------------------------------------------------

    @Test
    void moleBelowConfidenceThreshold_notAboveThreshold() {
        stubVision("mole", 0.40);   // a mole, but below the 0.6 threshold
        String token = issueToken(MoleTriageService.WIDGET_TYPE);

        MoleTriageResponse resp = postPhoto(token, FAKE_IMAGE, "image/jpeg", "maybe.jpg", null)
                .expectStatus().isOk()
                .expectBody(MoleTriageResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.classification()).isEqualTo("mole");
        assertThat(resp.confidence()).isEqualTo(0.40);
        assertThat(resp.aboveThreshold()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Wrong widgetType token → 401/4010, zero effect
    // -------------------------------------------------------------------------

    @Test
    void wrongWidgetTypeToken_401_4010_zeroEffect() {
        stubVision("mole", 0.92);
        String token = issueToken("service-request");   // wrong type

        postPhoto(token, FAKE_IMAGE, "image/jpeg", "yard.jpg", Map.of("phone", CALLER_PHONE))
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4010);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Contact.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Activity.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        assertThat(smsTo).isEmpty();
        assertThat(emailTo.get()).isNull();
    }

    // -------------------------------------------------------------------------
    // Tampered token → 401 (1602 range), zero effect
    // -------------------------------------------------------------------------

    @Test
    void tamperedToken_401_zeroEffect() {
        stubVision("mole", 0.92);
        String token = issueToken(MoleTriageService.WIDGET_TYPE);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        postPhoto(tampered, FAKE_IMAGE, "image/jpeg", "yard.jpg", Map.of("phone", CALLER_PHONE))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").value(o -> assertThat((Integer) o).isBetween(1600, 1699));

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Contact.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Missing image part → 400/4011
    // -------------------------------------------------------------------------

    @Test
    void missingImagePart_400_4011() {
        String token = issueToken(MoleTriageService.WIDGET_TYPE);

        postPhoto(token, null, null, null, Map.of("phone", CALLER_PHONE))
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4011);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Unsupported media type → 415/4012
    // -------------------------------------------------------------------------

    @Test
    void unsupportedMediaType_415_4012() {
        String token = issueToken(MoleTriageService.WIDGET_TYPE);

        postPhoto(token, "not-an-image".getBytes(), "application/pdf", "doc.pdf",
                Map.of("phone", CALLER_PHONE))
                .expectStatus().isEqualTo(415)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4012);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
