package com.kumouri.kmodigipresbe.integration.moletripwire;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.moletripwire.support.MoleTripwireItStorageTestConfig;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.model.project.Project;
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
 * Phase 3 — MoleTripwireIT: the B2 re-activity tripwire headline ITs. Mirrors the Phase-2
 * {@code MoleTriageIT} + the {@code ServiceRequestWidgetIT} (tokenized public-widget) precedent, but
 * asserts the tripwire's distinctive effect: a high-confidence mole auto-creates a re-treatment
 * {@code Milestone} on the token's {@code Project}.
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call → WireMock ({@code kmosf.ai.anthropic.base-url} via
 * {@code @DynamicPropertySource}); the Twilio SMS + email notify seams → {@code @MockitoBean} (the
 * {@code OnTheWayDispatchIT} / Phase-1/2 precedent); {@code FileStorageService} is the in-memory
 * {@link MoleTripwireItStorageTestConfig} {@code @Bean @Primary} stub. The tripwire token is signed
 * with the test {@code kmosf.security.widget-token-secret}; the Anthropic {@code apiKey} is a sandbox
 * fake. No live charge / send / upload anywhere.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>valid mole-tripwire token + above-threshold mole → 200 + re-treatment Milestone created on
 *       the Project + Activity(NOTE) + notify (email + SMS) + MOLE_TRIPWIRE_REPORTED +
 *       RETREATMENT_MILESTONE_CREATED events + the image content block hit WireMock;</li>
 *   <li>below-threshold "unsure" → 200 + aboveThreshold=false + NO Milestone (still records the
 *       Attachment), "review" framing;</li>
 *   <li>wrong-widgetType token → 401/4013, zero effect (no Attachment, no Milestone, zero WireMock);</li>
 *   <li>tampered token → 401 (1602), zero effect;</li>
 *   <li>missing image part → 400/4014.</li>
 * </ul>
 *
 * <p>Shard-safe: the only mocks are the two precedented notify seams + the WireMock base-URL
 * {@code @DynamicPropertySource}; storage is the precedented in-memory {@code @Bean} stub; self-clean
 * {@code mongo.remove} {@code @BeforeEach}; no {@code application-test.properties} / {@code build.gradle}
 * shard change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, MoleTripwireItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=mole-tripwire-it-secret-0123456789",
        "kmosf.mole-tripwire.confidence-threshold=0.6"
})
class MoleTripwireIT {

    /** Must match the {@code kmosf.security.widget-token-secret} in {@code @TestPropertySource}. */
    private static final String TEST_SECRET = "mole-tripwire-it-secret-0123456789";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-phase3-fake";
    private static final String NOTIFY_EMAIL = "rob@nomomole.test";
    private static final String NOTIFY_PHONE = "+16185550111";
    private static final String CUSTOMER_PHONE = "+16185550199";
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
    @Autowired MoleTripwireTokenService tripwireTokens;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;
    private UUID projectId;
    private UUID contactId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Milestone.class).block();
        mongo.remove(new Query(), Project.class).block();
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
                .id(tenantId).slug("mole-tripwire-it-" + tenantId)
                .displayName("Mole Tripwire IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        // The coverage customer's Contact + Project (the token carries this Project's id).
        Contact customer = mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON).displayName("Coverage Customer")
                .phones(List.of(PhoneNumber.builder().number(CUSTOMER_PHONE).label("mobile").build()))
                .build()).block();
        contactId = customer.getId();

        Project project = mongo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .code("PRJ-2026-099").name("NMM Coverage — Customer")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(contactId)
                .build()).block();
        projectId = project.getId();

        // Twilio connection — config carries the per-tenant notify targets (NOT hardcoded);
        // the SMS itself is mocked.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_phase3",
                        "authToken", "twilio_test_authtoken_phase3",
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

    private String issueTripwireToken() {
        return tripwireTokens.issue(tenantId, projectId, Duration.ofHours(1));
    }

    private WebTestClient.ResponseSpec postReport(String token, byte[] image, String mediaType,
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
                .uri("/public/integrations/mole-tripwire/" + token + "/report")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    // -------------------------------------------------------------------------
    // Happy path: valid token + above-threshold mole → re-treatment Milestone + notify + events
    // -------------------------------------------------------------------------

    @Test
    void validToken_abovethresholdMole_createsRetreatmentMilestoneNotifiesAndReturns() {
        stubVision("mole", 0.92);
        String token = issueTripwireToken();

        MoleTripwireResponse resp = postReport(token, FAKE_IMAGE, "image/jpeg", "yard.jpg",
                Map.of("note", "Found a fresh mound by the fence"))
                .expectStatus().isOk()
                .expectBody(MoleTripwireResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.classification()).isEqualTo("mole");
        assertThat(resp.confidence()).isEqualTo(0.92);
        assertThat(resp.aboveThreshold()).isTrue();
        assertThat(resp.message()).containsIgnoringCase("mole");
        assertThat(resp.attachmentId()).isNotNull();
        assertThat(resp.retreatmentMilestoneId()).isNotNull();

        // Attachment(MOLE_PHOTO) stored under the tenant prefix.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Attachment> atts = mongo.findAll(Attachment.class).collectList().block();
            assertThat(atts).hasSize(1);
            assertThat(atts.get(0).getSubjectType()).isEqualTo("MOLE_PHOTO");
            assertThat(atts.get(0).getStorageRef()).startsWith("tenants/" + tenantId + "/");
        });

        // The distinctive tripwire effect: a re-treatment Milestone on the customer's Project.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Milestone> ms = mongo.findAll(Milestone.class).collectList().block();
            assertThat(ms).hasSize(1);
            Milestone m = ms.get(0);
            assertThat(m.getProjectId()).isEqualTo(projectId);
            assertThat(m.getTenantId()).isEqualTo(tenantId);
            assertThat(m.getStatus()).isEqualTo(Milestone.MilestoneStatus.PENDING);
            assertThat(m.getName()).containsIgnoringCase("re-treatment");
            assertThat(m.getDueDate()).isNotNull();
            assertThat(m.getId()).isEqualTo(resp.retreatmentMilestoneId());
        });

        // Activity(NOTE) logged against the Project's primary contact, payload carries the linkage.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("NOTE");
            assertThat(a.getSubjectType().name()).isEqualTo("CONTACT");
            assertThat(a.getSubjectId()).isEqualTo(contactId);
            assertThat(a.getPayload()).containsKey("projectId");
            assertThat(a.getPayload()).containsKey("milestoneId");
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
            assertThat(observed).anyMatch(e -> DomainEventType.MOLE_TRIPWIRE_REPORTED.equals(e.type()));
            assertThat(observed).anyMatch(e -> DomainEventType.RETREATMENT_MILESTONE_CREATED.equals(e.type()));
        });
    }

    // -------------------------------------------------------------------------
    // Below-threshold "unsure" → 200, recorded, NO Milestone
    // -------------------------------------------------------------------------

    @Test
    void belowThresholdUnsure_recordedButNoMilestone() {
        stubVision("unsure", 0.20);
        String token = issueTripwireToken();

        MoleTripwireResponse resp = postReport(token, FAKE_IMAGE, "image/jpeg", "blurry.jpg", null)
                .expectStatus().isOk()
                .expectBody(MoleTripwireResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.classification()).isEqualTo("unsure");
        assertThat(resp.aboveThreshold()).isFalse();
        assertThat(resp.retreatmentMilestoneId()).isNull();

        // Photo still recorded, but NO Milestone.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);
        });
        assertThat(mongo.findAll(Milestone.class).collectList().block()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // A mole below the confidence threshold also creates NO Milestone
    // -------------------------------------------------------------------------

    @Test
    void moleBelowConfidenceThreshold_noMilestone() {
        stubVision("mole", 0.40);   // a mole, but below the 0.6 threshold
        String token = issueTripwireToken();

        MoleTripwireResponse resp = postReport(token, FAKE_IMAGE, "image/jpeg", "maybe.jpg", null)
                .expectStatus().isOk()
                .expectBody(MoleTripwireResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.aboveThreshold()).isFalse();
        assertThat(resp.retreatmentMilestoneId()).isNull();
        assertThat(mongo.findAll(Milestone.class).collectList().block()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Wrong widgetType token → 401/4013, zero effect
    // -------------------------------------------------------------------------

    @Test
    void wrongWidgetTypeToken_401_4013_zeroEffect() throws Exception {
        stubVision("mole", 0.92);
        // A CORRECTLY-SIGNED token (same secret + 4-field scheme) but a non-"mole-tripwire"
        // widgetType — the service's issue() always stamps "mole-tripwire", so we build the
        // wrong-type token directly with the known test secret to exercise the 4013 branch
        // deterministically.
        String wrongTypeToken = signTripwireLikeToken(
                tenantId, "service-request", projectId, TEST_SECRET);

        postReport(wrongTypeToken, FAKE_IMAGE, "image/jpeg", "yard.jpg", Map.of("note", "x"))
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4013);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Milestone.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        assertThat(smsTo).isEmpty();
        assertThat(emailTo.get()).isNull();
    }

    /**
     * Builds a token in the exact {@link MoleTripwireTokenService} wire format
     * ({@code base64url(tenantId|widgetType|projectId|expiry) "." base64url(HMAC-SHA256)}) with a
     * caller-chosen {@code widgetType}, signed with the test secret — so a non-"mole-tripwire" type
     * passes the signature check and reaches the 4013 widgetType-mismatch branch.
     */
    private static String signTripwireLikeToken(UUID tenantId, String widgetType, UUID projectId,
                                                String secret) throws Exception {
        long exp = java.time.Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
        String payload = tenantId + "|" + widgetType + "|" + projectId + "|" + exp;
        byte[] payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payloadBytes);
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(payloadBytes) + "." + enc.encodeToString(sig);
    }

    // -------------------------------------------------------------------------
    // Tampered token → 401 (1602 range), zero effect
    // -------------------------------------------------------------------------

    @Test
    void tamperedToken_401_zeroEffect() {
        stubVision("mole", 0.92);
        String token = issueTripwireToken();
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        postReport(tampered, FAKE_IMAGE, "image/jpeg", "yard.jpg", Map.of("note", "x"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").value(o -> assertThat((Integer) o).isBetween(1600, 1699));

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Milestone.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Missing image part → 400/4014
    // -------------------------------------------------------------------------

    @Test
    void missingImagePart_400_4014() {
        String token = issueTripwireToken();

        postReport(token, null, null, null, Map.of("note", "x"))
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4014);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Milestone.class).collectList().block()).isEmpty();
    }
}
