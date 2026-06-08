package com.kumouri.kmodigipresbe.integration.equipmentvision;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.equipmentvision.support.EquipmentVisionItStorageTestConfig;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.files.Attachment;
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
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HS-2 — EquipmentVisionIT: the equipment-nameplate photo-enrichment headline ITs. Mirrors the
 * Phase-3 {@code MoleTripwireIT} (tokenized, entity-bound public upload; WireMock Anthropic via
 * {@code @DynamicPropertySource}; {@code @MockitoBean} SMS/email notify seams; in-memory storage
 * {@code @Bean @Primary} stub) but asserts the HS-2 distinctive effect: a legible nameplate read via
 * the shared {@code AiVisionService.extract} <strong>enriches the bound DRAFT WorkOrder</strong>
 * (customFields + notes) + logs an {@code Activity(NOTE, WORK_ORDER)} + notifies the owner — all
 * best-effort (a vision failure never drops or corrupts the lead).
 *
 * <h2>Design fork (plan §3) — tokenized upload, NOT an inbound-MMS webhook</h2>
 * The photo arrives over {@code POST /public/integrations/home-services/equipment-photo/{token}/upload}
 * (the {@code MoleTriageController}/{@code MoleTripwireController} pattern), so there is no inbound
 * Twilio MMS webhook and no 10DLC surface. The token (signed with the test
 * {@code kmosf.security.widget-token-secret}) binds the tenant + the target WorkOrder.
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call → WireMock; the Twilio SMS + email notify seams → {@code @MockitoBean};
 * {@code FileStorageService} is the in-memory {@link EquipmentVisionItStorageTestConfig} stub; the
 * Anthropic {@code apiKey} is a sandbox fake. No live charge / send / upload anywhere.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>happy nameplate read → 200, WorkOrder enriched ({@code customFields.equipmentMake=Carrier},
 *       model/serial, notes appended), Activity(NOTE, WORK_ORDER), owner notify, the image content
 *       block hit WireMock, {@code EQUIPMENT_PHOTO_READ} event {@code enriched=true};</li>
 *   <li>vision failure (WireMock 500) → 200 best-effort, WorkOrder customFields/notes UNCHANGED, the
 *       photo Attachment still stored, no error;</li>
 *   <li>blank read (model returns an empty object) → 200 {@code enriched=false}, WorkOrder unchanged,
 *       Attachment stored, NO Activity;</li>
 *   <li>missing image part → 400/4213, zero effect;</li>
 *   <li>wrong-widgetType token → 401/4210, zero effect (no Attachment, zero WireMock);</li>
 *   <li>tampered token → 401 (1600-range), zero effect.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, EquipmentVisionItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=equipment-vision-it-secret-0123456789",
        "kmosf.home-services.equipment-vision-model=claude-sonnet-4-5"
})
class EquipmentVisionIT {

    /** Must match the {@code kmosf.security.widget-token-secret} in {@code @TestPropertySource}. */
    private static final String TEST_SECRET = "equipment-vision-it-secret-0123456789";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-hs2-fake";
    private static final String NOTIFY_EMAIL = "owner@hvac.test";
    private static final String NOTIFY_PHONE = "+16185550111";
    private static final byte[] FAKE_IMAGE = "fake-jpeg-nameplate-bytes".getBytes();

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
    @Autowired EquipmentPhotoTokenService photoTokens;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;
    private UUID workOrderId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Activity.class).block();
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
                .id(tenantId).slug("equipment-vision-it-" + tenantId)
                .displayName("Equipment Vision IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        // The DRAFT WorkOrder a home-services voicemail created (HS-1); the token binds to its id.
        Map<String, Object> cf = new HashMap<>();
        cf.put("urgency", "URGENT");
        cf.put("callSid", "CA_hs2_seed");
        cf.put("source", "voicemail");
        WorkOrder wo = mongo.save(WorkOrder.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .workOrderNumber("2026-06-0001")
                .status(WorkOrderStatus.DRAFT)
                .serviceType("HVAC")
                .title("HVAC — URGENT")
                .notes("Missed-call voicemail lead (home services).\nSymptom: no heat")
                .customFields(cf)
                .build()).block();
        workOrderId = wo.getId();

        // Twilio connection — config carries the per-tenant notify targets (NOT hardcoded); SMS mocked.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_hs2",
                        "authToken", "twilio_test_authtoken_hs2",
                        "fromNumber", "+16185550100")))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL,
                        "notifyPhone", NOTIFY_PHONE)))
                .build()).block();

        // Anthropic connection — sandbox apiKey so AiVisionService resolves a key.
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

    private void stubNameplate(String make, String model, String serial, String type, String symptom) {
        String inner = "{\\\"make\\\":\\\"" + make + "\\\",\\\"model\\\":\\\"" + model
                + "\\\",\\\"serial\\\":\\\"" + serial + "\\\",\\\"equipmentType\\\":\\\"" + type
                + "\\\",\\\"observedSymptom\\\":\\\"" + symptom + "\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":40}}")));
    }

    private void stubBlankRead() {
        // The model returns a well-formed but all-null nameplate JSON (nothing legible).
        String inner = "{\\\"make\\\":null,\\\"model\\\":null,\\\"serial\\\":null,"
                + "\\\"equipmentType\\\":null,\\\"observedSymptom\\\":null}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":10}}")));
    }

    private String issuePhotoToken() {
        return photoTokens.issue(tenantId, workOrderId, Duration.ofHours(1));
    }

    private WebTestClient.ResponseSpec postUpload(String token, byte[] image, String mediaType,
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
                .uri("/public/integrations/home-services/equipment-photo/" + token + "/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    // -------------------------------------------------------------------------
    // Happy path: legible nameplate → WorkOrder enriched + Activity + notify + event
    // -------------------------------------------------------------------------

    @Test
    void validToken_legibleNameplate_enrichesWorkOrderActivityAndNotifies() {
        stubNameplate("Carrier", "58STA", "1234ABC", "furnace", "rust on heat exchanger");
        String token = issuePhotoToken();

        EquipmentPhotoResponse resp = postUpload(token, FAKE_IMAGE, "image/jpeg", "nameplate.jpg",
                Map.of("note", "On the side of the furnace"))
                .expectStatus().isOk()
                .expectBody(EquipmentPhotoResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.enriched()).isTrue();
        assertThat(resp.make()).isEqualTo("Carrier");
        assertThat(resp.model()).isEqualTo("58STA");
        assertThat(resp.serial()).isEqualTo("1234ABC");
        assertThat(resp.attachmentId()).isNotNull();

        // Attachment(WORK_ORDER) stored under the tenant prefix, bound to the WorkOrder.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Attachment> atts = mongo.findAll(Attachment.class).collectList().block();
            assertThat(atts).hasSize(1);
            assertThat(atts.get(0).getSubjectType()).isEqualTo("WORK_ORDER");
            assertThat(atts.get(0).getSubjectId()).isEqualTo(workOrderId);
            assertThat(atts.get(0).getStorageRef()).startsWith("tenants/" + tenantId + "/");
        });

        // The distinctive HS-2 effect: the DRAFT WorkOrder is enriched with the nameplate fields.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            WorkOrder wo = mongo.findById(workOrderId, WorkOrder.class).block();
            assertThat(wo).isNotNull();
            assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.DRAFT);     // untouched
            assertThat(wo.getServiceType()).isEqualTo("HVAC");               // untouched
            assertThat(wo.getCustomFields()).containsEntry("equipmentMake", "Carrier");
            assertThat(wo.getCustomFields()).containsEntry("equipmentModel", "58STA");
            assertThat(wo.getCustomFields()).containsEntry("equipmentSerial", "1234ABC");
            assertThat(wo.getCustomFields()).containsEntry("equipmentType", "furnace");
            assertThat(wo.getCustomFields()).containsEntry("observedSymptom", "rust on heat exchanger");
            // HS-1's fields survive the merge.
            assertThat(wo.getCustomFields()).containsEntry("urgency", "URGENT");
            assertThat(wo.getNotes()).contains("Equipment (from photo)");
            assertThat(wo.getNotes()).contains("Carrier 58STA");
            assertThat(wo.getNotes()).contains("no heat");  // original note preserved
        });

        // Activity(NOTE, WORK_ORDER) logged against the WorkOrder, payload carries the read.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).hasSize(1);
            Activity a = acts.get(0);
            assertThat(a.getType().name()).isEqualTo("NOTE");
            assertThat(a.getSubjectType().name()).isEqualTo("WORK_ORDER");
            assertThat(a.getSubjectId()).isEqualTo(workOrderId);
            assertThat(a.getPayload()).containsEntry("equipmentMake", "Carrier");
            assertThat(a.getPayload()).containsKey("attachmentId");
        });

        // Notify owner (email + SMS to the per-tenant config targets).
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
            assertThat(smsTo).contains(NOTIFY_PHONE);
        });

        // The vision call actually went to WireMock WITH an image content block (§7 + plan test req).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].type",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("image"))));

        // Advisory event observed, enriched=true.
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.EQUIPMENT_PHOTO_READ.equals(e.type())
                                && Boolean.TRUE.equals(e.payload().get("enriched"))));
    }

    // -------------------------------------------------------------------------
    // Vision failure (WireMock 500) → best-effort: WorkOrder UNCHANGED, photo stored, no error
    // -------------------------------------------------------------------------

    @Test
    void visionUpstreamFailure_degradesBestEffort_workOrderUnchanged() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));
        String token = issuePhotoToken();

        EquipmentPhotoResponse resp = postUpload(token, FAKE_IMAGE, "image/jpeg", "nameplate.jpg", null)
                .expectStatus().isOk()
                .expectBody(EquipmentPhotoResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.enriched()).as("a vision failure enriches nothing").isFalse();
        assertThat(resp.attachmentId()).as("photo still stored").isNotNull();

        // The photo is still stored.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1));

        // The WorkOrder customFields/notes are UNCHANGED (no equipment* keys, original notes intact).
        WorkOrder wo = mongo.findById(workOrderId, WorkOrder.class).block();
        assertThat(wo).isNotNull();
        assertThat(wo.getCustomFields()).doesNotContainKey("equipmentMake");
        assertThat(wo.getCustomFields()).doesNotContainKey("equipmentPhotoAttachmentId");
        assertThat(wo.getNotes()).doesNotContain("Equipment (from photo)");
        // No Activity created on a degraded read.
        assertThat(mongo.findAll(Activity.class).collectList().block()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Blank read (model returns an empty/all-null nameplate) → enriched=false, no WO mutation
    // -------------------------------------------------------------------------

    @Test
    void blankNameplateRead_noEnrichment_noActivity() {
        stubBlankRead();
        String token = issuePhotoToken();

        EquipmentPhotoResponse resp = postUpload(token, FAKE_IMAGE, "image/jpeg", "blurry.jpg", null)
                .expectStatus().isOk()
                .expectBody(EquipmentPhotoResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.enriched()).isFalse();

        // Photo stored, but the WorkOrder is untouched and no Activity logged.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1));
        WorkOrder wo = mongo.findById(workOrderId, WorkOrder.class).block();
        assertThat(wo).isNotNull();
        assertThat(wo.getCustomFields()).doesNotContainKey("equipmentMake");
        assertThat(mongo.findAll(Activity.class).collectList().block()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Missing image part → 400/4213, zero effect
    // -------------------------------------------------------------------------

    @Test
    void missingImagePart_400_4213() {
        String token = issuePhotoToken();

        postUpload(token, null, null, null, Map.of("note", "x"))
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4213);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        WorkOrder wo = mongo.findById(workOrderId, WorkOrder.class).block();
        assertThat(wo.getCustomFields()).doesNotContainKey("equipmentMake");
    }

    // -------------------------------------------------------------------------
    // Wrong widgetType token → 401/4210, zero effect
    // -------------------------------------------------------------------------

    @Test
    void wrongWidgetTypeToken_401_4210_zeroEffect() throws Exception {
        stubNameplate("Carrier", "58STA", "1234", "furnace", "rust");
        // A correctly-signed token (same secret + 4-field scheme) but a non-"equipment-photo"
        // widgetType — issue() always stamps "equipment-photo", so build the wrong-type token
        // directly with the known test secret to exercise the 4210 branch deterministically.
        String wrongTypeToken = signPhotoLikeToken(
                tenantId, "mole-triage", workOrderId, TEST_SECRET);

        postUpload(wrongTypeToken, FAKE_IMAGE, "image/jpeg", "nameplate.jpg", Map.of("note", "x"))
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4210);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        assertThat(smsTo).isEmpty();
        assertThat(emailTo.get()).isNull();
    }

    /**
     * Builds a token in the exact {@link EquipmentPhotoTokenService} wire format
     * ({@code base64url(tenantId|widgetType|workOrderId|expiry) "." base64url(HMAC-SHA256)}) with a
     * caller-chosen {@code widgetType}, signed with the test secret — so a non-"equipment-photo" type
     * passes the signature check and reaches the 4210 widgetType-mismatch branch.
     */
    private static String signPhotoLikeToken(UUID tenantId, String widgetType, UUID workOrderId,
                                             String secret) throws Exception {
        long exp = java.time.Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
        String payload = tenantId + "|" + widgetType + "|" + workOrderId + "|" + exp;
        byte[] payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payloadBytes);
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(payloadBytes) + "." + enc.encodeToString(sig);
    }

    // -------------------------------------------------------------------------
    // Tampered token → 401 (1600-range), zero effect
    // -------------------------------------------------------------------------

    @Test
    void tamperedToken_401_zeroEffect() {
        stubNameplate("Carrier", "58STA", "1234", "furnace", "rust");
        String token = issuePhotoToken();
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        postUpload(tampered, FAKE_IMAGE, "image/jpeg", "nameplate.jpg", Map.of("note", "x"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").value(o -> assertThat((Integer) o).isBetween(1600, 1699));

        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
