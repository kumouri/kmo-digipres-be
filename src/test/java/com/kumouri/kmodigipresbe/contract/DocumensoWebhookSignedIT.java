package com.kumouri.kmodigipresbe.contract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.contract.support.ContractItStorageTestConfig;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.contract.DocumensoWebhookEvent;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.7 — DocumensoWebhookSignedIT: the AC-F1/AC-F2/AC-F3 headline test.
 *
 * <p>Mirrors {@code StripeWebhookIdempotencyIT} (the mandated shape):
 * {@code @SpringBootTest(RANDOM_PORT)} + {@code @AutoConfigureWebTestClient} +
 * {@code @Import(TestcontainersConfiguration.class, ContractItStorageTestConfig.class)} +
 * {@code @TestPropertySource} job disables;
 * {@code mongo.remove/findAll/findById} bypass tenant scope;
 * {@code eventPublisher.stream().subscribe(observed)} + {@code Awaitility}.
 *
 * <p><b>Corrected against the real Documenso product:</b> the webhook is
 * authenticated by the plain {@code X-Documenso-Secret} header (the verbatim
 * webhook secret, constant-time-compared) — NOT an HMAC over the body. The event
 * body uses {@code "event":"DOCUMENT_COMPLETED"} with the document id at
 * {@code "payload":{"id":<integer>}}. The {@code GET /documents/{id}/download}
 * stub returns JSON {@code {downloadUrl}} (a presigned URL — per the spec), and a
 * second stub serves the PDF bytes at that URL.
 *
 * <p>{@link com.kumouri.kmodigipresbe.service.storage.FileStorageService} is provided
 * by {@link ContractItStorageTestConfig} — an in-memory stub declared as a real
 * {@code @Bean @Primary} (no {@code @MockBean}) so this IT and
 * {@code DocumensoSendWireMockIT} share ONE Spring ApplicationContext cache key
 * (F.10 de-splinter — the #58-proven approach).
 *
 * <h2>No live Documenso (§7)</h2>
 * {@code kmosf.documenso.api-base-url} pointed at WireMock. The signed-PDF download
 * endpoint is stubbed to return fake PDF bytes. The in-memory storage stub absorbs
 * the {@code putBytes} call so we can assert {@code signedPdfStorageRef} is set
 * non-null under the tenant prefix (legal-integrity: the store call is exercised;
 * bytes don't round-trip a real bucket in the IT).
 *
 * <h2>AC-F1 (legal-signature happy path)</h2>
 * POST signed webhook (valid HMAC) → 200; Awaitility:
 * {@code Contract.signedAt != null}, {@code signedPdfStorageRef} non-null under tenant prefix,
 * {@code status == SIGNED}.
 *
 * <h2>AC-F2 (signed SOW → Deal WON + exactly one Project; exactly-once)</h2>
 * The Deal is WON + exactly ONE Project exists for that deal +
 * {@code contract.promotedDealToWon == true} + {@code spawnedProjectId} set +
 * {@code CONTRACT_SIGNED} observed. Re-deliver the same event → 200 no-op: still one
 * ledger row, still one Project, Deal still WON, {@code signedAt} unchanged.
 *
 * <h2>AC-F3 (secret mismatch → 401 / errorCode 3710, zero side effects)</h2>
 * Wrong {@code X-Documenso-Secret} → 401 + {@code errorCode == 3710} + zero ledger
 * rows + {@code signedAt == null} + zero Projects. Missing secret → same.
 * Unknown-tenant path → 3711.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, ContractItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class DocumensoWebhookSignedIT {

    // The verbatim Documenso webhook secret (sent in X-Documenso-Secret).
    private static final String TEST_WEBHOOK_SECRET = "whsec_test_phaseF_documenso_supersecret";
    // The Documenso document id is an INTEGER end-to-end. The Contract stores its
    // canonical string form; the webhook payload.id carries the integer.
    private static final long DOC_ID_INT = 101L;
    private static final String DOCUMENSO_DOCUMENT_ID = Long.toString(DOC_ID_INT);
    // Path the download JSON envelope points its presigned downloadUrl at.
    private static final String DOWNLOAD_BYTES_PATH = "/signed-bytes/doc-" + DOC_ID_INT;

    // WireMock stubs the two-hop signed-PDF download (JSON envelope + bytes).
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
    static void documensoProps(DynamicPropertyRegistry registry) {
        // §7 boundary: every Documenso call goes to WireMock.
        registry.add("kmosf.documenso.api-base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private UUID dealId;
    private UUID contractId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), DocumensoWebhookEvent.class).block();
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("webhook-signed-" + tenantId)
                .displayName("Webhook Signed Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        // Seed IntegrationConnection with webhookSigningSecret + apiToken (for downloadSignedPdf)
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("documenso")
                .secrets(new HashMap<>(Map.of(
                        "webhookSigningSecret", TEST_WEBHOOK_SECRET,
                        "apiToken", "api_test_phaseF_fake_token")))
                .build()).block();

        // Seed a Deal in a non-WON stage (PROPOSAL)
        dealId = UUID.randomUUID();
        mongo.save(Deal.builder()
                .id(dealId).tenantId(tenantId)
                .title("Deal for SOW Contract").stage(PipelineStage.PROPOSAL)
                .build()).block();

        // Seed a SOW Contract in SENT status, correlated to the Documenso document id
        contractId = UUID.randomUUID();
        mongo.save(Contract.builder()
                .id(contractId).tenantId(tenantId)
                .title("SOW Agreement").kind(ContractTemplate.Kind.SOW)
                .status(Contract.Status.SENT)
                .documensoDocumentId(DOCUMENSO_DOCUMENT_ID)
                .dealId(dealId)
                .promotedDealToWon(false)
                .build()).block();

        // WireMock: two-hop signed-PDF download (corrected against the spec).
        // Hop 1: GET /api/v1/documents/{id}/download returns JSON {downloadUrl}
        // (a presigned URL), NOT raw bytes.
        wireMock.stubFor(get(urlPathMatching("/api/v1/documents/.*/download"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"downloadUrl\":\"" + wireMock.baseUrl()
                                + DOWNLOAD_BYTES_PATH + "\"}")));
        // Hop 2: GET that presigned URL returns the fake PDF bytes.
        wireMock.stubFor(get(urlPathEqualTo(DOWNLOAD_BYTES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(new byte[]{0x25, 0x50, 0x44, 0x46}))); // %PDF header

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    // -------------------------------------------------------------------------
    // Real Documenso webhook body: event type DOCUMENT_COMPLETED, document id at
    // payload.id (an INTEGER). No HMAC — the webhook is authenticated by the plain
    // X-Documenso-Secret header (constant-time-compared to the stored secret).
    // -------------------------------------------------------------------------

    private String completedWebhookBody(String eventId) {
        return "{\"id\":\"" + eventId + "\","
                + "\"event\":\"DOCUMENT_COMPLETED\","
                + "\"payload\":{"
                + "\"id\":" + DOC_ID_INT   // INTEGER, no quotes
                + "}}";
    }

    /**
     * POSTs the webhook with the given {@code X-Documenso-Secret} header value (null
     * = omit the header entirely).
     */
    private void postWebhook(String body, String secret, int expectedStatus) {
        WebTestClient.RequestHeadersSpec<?> req = web.post()
                .uri("/public/integrations/documenso/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
        if (secret != null) {
            req = ((WebTestClient.RequestBodySpec) req)
                    .header("X-Documenso-Secret", secret);
        }
        req.exchange().expectStatus().isEqualTo(expectedStatus);
    }

    // -------------------------------------------------------------------------
    // AC-F1 + AC-F2: signed webhook → signedAt + signedPdfStorageRef + WON + Project
    // -------------------------------------------------------------------------

    @Test
    void signedWebhook_acF1_signedAtStorageRef_acF2_dealWon_exactlyOneProject() {
        String eventId = "evt_phaseF_signed_" + UUID.randomUUID();
        String body = completedWebhookBody(eventId);

        // POST valid DOCUMENT_COMPLETED webhook with the correct secret → 200
        postWebhook(body, TEST_WEBHOOK_SECRET, 200);

        // AC-F1: Awaitility — Contract.signedAt non-null, signedPdfStorageRef non-null, SIGNED
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Contract c = mongo.findById(contractId, Contract.class).block();
            assertThat(c.getSignedAt())
                    .as("AC-F1: Contract.signedAt must be set after signed webhook")
                    .isNotNull();
            assertThat(c.getSignedPdfStorageRef())
                    .as("AC-F1: Contract.signedPdfStorageRef must be set under tenant prefix")
                    .isNotNull()
                    .startsWith("tenants/" + tenantId + "/");
            assertThat(c.getStatus())
                    .as("AC-F1: Contract status must be SIGNED")
                    .isEqualTo(Contract.Status.SIGNED);
        });

        // AC-F2: Deal WON + exactly one Project
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Project> projects = mongo.findAll(Project.class).collectList().block();
            assertThat(projects)
                    .as("AC-F2: exactly one Project must be spawned for the deal")
                    .hasSize(1);
            assertThat(projects.get(0).getDealId())
                    .as("AC-F2: Project.dealId must match the deal")
                    .isEqualTo(dealId);
        });

        Contract c = mongo.findById(contractId, Contract.class).block();
        assertThat(c.isPromotedDealToWon())
                .as("AC-F2: contract.promotedDealToWon must be true")
                .isTrue();
        assertThat(c.getSpawnedProjectId())
                .as("AC-F2: contract.spawnedProjectId must be set")
                .isNotNull();

        Deal deal = mongo.findById(dealId, Deal.class).block();
        assertThat(deal.getStage())
                .as("AC-F2: Deal stage must be WON")
                .isEqualTo(PipelineStage.WON);

        // CONTRACT_SIGNED observed
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.CONTRACT_SIGNED.equals(e.type())));

        // One ledger row written
        List<DocumensoWebhookEvent> ledger = mongo.findAll(DocumensoWebhookEvent.class)
                .collectList().block();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getDocumensoEventId()).isEqualTo(eventId);
    }

    // -------------------------------------------------------------------------
    // AC-F2 exactly-once: re-deliver same event → 200 no-op
    // -------------------------------------------------------------------------

    @Test
    void redeliverSameEventId_exactlyOnce_noDoubleEffect() {
        String eventId = "evt_phaseF_redelivery_" + UUID.randomUUID();
        String body = completedWebhookBody(eventId);

        // First delivery — sets signedAt, creates Project
        postWebhook(body, TEST_WEBHOOK_SECRET, 200);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Contract c = mongo.findById(contractId, Contract.class).block();
            assertThat(c.getSignedAt()).isNotNull();
        });

        // Capture the signedAt for unchanged-assertion below
        java.time.Instant signedAt = mongo.findById(contractId, Contract.class)
                .block().getSignedAt();

        // Re-deliver the SAME event id → 200 no-op (explicit-boolean idempotency)
        postWebhook(body, TEST_WEBHOOK_SECRET, 200);

        // Allow brief processing window
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Still exactly ONE ledger row
        List<DocumensoWebhookEvent> ledger = mongo.findAll(DocumensoWebhookEvent.class)
                .collectList().block();
        assertThat(ledger)
                .as("AC-F2 exactly-once: re-delivery must not create a second ledger row")
                .hasSize(1);

        // Still exactly ONE Project
        List<Project> projects = mongo.findAll(Project.class).collectList().block();
        assertThat(projects)
                .as("AC-F2 exactly-once: re-delivery must not spawn a second Project")
                .hasSize(1);

        // Deal still WON
        assertThat(mongo.findById(dealId, Deal.class).block().getStage())
                .as("AC-F2 exactly-once: Deal must still be WON after re-delivery")
                .isEqualTo(PipelineStage.WON);

        // signedAt unchanged
        assertThat(mongo.findById(contractId, Contract.class).block().getSignedAt())
                .as("AC-F2 exactly-once: signedAt must not be altered by re-delivery")
                .isEqualTo(signedAt);
    }

    // -------------------------------------------------------------------------
    // AC-F3: wrong X-Documenso-Secret → 401 / errorCode 3710, zero side effects
    // -------------------------------------------------------------------------

    @Test
    void wrongSecret_401_3710_zeroSideEffects() {
        String body = completedWebhookBody("evt_phaseF_badsecret_" + UUID.randomUUID());

        postWebhook(body, "the_wrong_secret", 401);

        // Verify via the problem+json response (expectStatus already verified 401 above)
        web.post()
                .uri("/public/integrations/documenso/" + tenantId + "/webhook")
                .header("X-Documenso-Secret", "the_wrong_secret")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3710);

        // AC-F3: zero side effects
        assertThat(mongo.findAll(DocumensoWebhookEvent.class).collectList().block())
                .as("AC-F3: wrong secret must not produce a ledger row")
                .isEmpty();
        assertThat(mongo.findById(contractId, Contract.class).block().getSignedAt())
                .as("AC-F3: wrong secret must not set signedAt")
                .isNull();
        assertThat(mongo.findAll(Project.class).collectList().block())
                .as("AC-F3: wrong secret must not spawn a Project")
                .isEmpty();
    }

    @Test
    void missingSecret_401_3710_zeroSideEffects() {
        String body = completedWebhookBody("evt_phaseF_nosecret_" + UUID.randomUUID());

        // No X-Documenso-Secret header at all
        web.post()
                .uri("/public/integrations/documenso/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3710);

        // Zero side effects
        assertThat(mongo.findAll(DocumensoWebhookEvent.class).collectList().block()).isEmpty();
        assertThat(mongo.findById(contractId, Contract.class).block().getSignedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // AC-F3: unknown-tenant path → 3711
    // -------------------------------------------------------------------------

    @Test
    void unknownTenant_invalidUuid_3711() {
        String body = completedWebhookBody("evt_phaseF_unknowntenant");

        web.post()
                .uri("/public/integrations/documenso/not-a-uuid/webhook")
                .header("X-Documenso-Secret", "irrelevant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3711);
    }
}
