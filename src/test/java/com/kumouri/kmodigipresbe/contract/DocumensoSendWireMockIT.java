package com.kumouri.kmodigipresbe.contract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.contract.support.ContractItStorageTestConfig;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.7 — DocumensoSendWireMockIT: contract send flow — WireMock Documenso (§7 hard boundary).
 *
 * <p>{@code kmosf.documenso.api-base-url} pointed at WireMock via {@code @DynamicPropertySource}.
 * {@link com.kumouri.kmodigipresbe.service.storage.FileStorageService} is provided by
 * {@link ContractItStorageTestConfig} — an in-memory stub declared as a real
 * {@code @Bean @Primary} (no {@code @MockBean}) so this IT and
 * {@code DocumensoWebhookSignedIT} share ONE Spring ApplicationContext cache key
 * (F.10 de-splinter — the #58-proven approach).
 *
 * <p>Asserts:
 * <ul>
 *   <li>POST /contracts/{id}/send + Idempotency-Key → SENT, documensoDocumentId set,
 *       renderedPdfStorageRef set; exactly one WireMock send POST</li>
 *   <li>No apiToken → 3720 / 412</li>
 *   <li>Re-send when already SENT → idempotent (no second WireMock POST)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, ContractItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class DocumensoSendWireMockIT {

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
        // §7 boundary: every Documenso call goes to WireMock, never a real host.
        registry.add("kmosf.documenso.api-base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IntegrationConnectionRepository connections;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("send-wm-" + tenantId)
                .displayName("Send WireMock Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@sendwm.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);
    }

    private void seedDocumensoConn(boolean withApiToken) {
        Map<String, String> secrets = new HashMap<>();
        if (withApiToken) {
            // Sandbox-shaped fake token — NOT a live credential (§7).
            secrets.put("apiToken", "dt_test_phaseF_fake_token");
        }
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("documenso")
                .secrets(secrets)
                .build()).block();
    }

    private UUID seedDraftContract() {
        ContractTemplate tmpl = ContractTemplate.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Test SOW").bodyTemplate("SOW content for {{title}}")
                .kind(ContractTemplate.Kind.SOW).active(true).build();
        mongo.save(tmpl).block();

        Contract contract = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Test Contract").kind(ContractTemplate.Kind.SOW)
                .status(Contract.Status.DRAFT).templateId(tmpl.getId()).build();
        return mongo.save(contract).block().getId();
    }

    // -------------------------------------------------------------------------
    // Happy path: send → SENT, documensoDocumentId set, one WireMock call
    // -------------------------------------------------------------------------

    @Test
    void send_successPath_sentStatus_docIdSet_oneWireMockCall() {
        seedDocumensoConn(true);
        UUID contractId = seedDraftContract();

        wireMock.stubFor(post(urlPathEqualTo("/api/v1/documents"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"documentId\":\"doc_test_1\",\"status\":\"PENDING\"}")));

        web.post().uri("/contracts/" + contractId + "/send")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SENT")
                .jsonPath("$.documensoDocumentId").isEqualTo("doc_test_1")
                .jsonPath("$.renderedPdfStorageRef").isNotEmpty();

        // Exactly one WireMock send POST — no request targeted a live Documenso host
        // (the stub being hit at all proves the WireMock base-URL was used).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/documents")));
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // No apiToken → 3720 / 412
    // -------------------------------------------------------------------------

    @Test
    void noApiToken_412_3720_noWireMockCall() {
        seedDocumensoConn(false); // connection present but no apiToken
        UUID contractId = seedDraftContract();

        web.post().uri("/contracts/" + contractId + "/send")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(412)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3720);

        // No Documenso call attempted
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Re-send when already SENT → idempotent (no second WireMock POST)
    // -------------------------------------------------------------------------

    @Test
    void resend_alreadySent_idempotent_noSecondWireMockCall() {
        seedDocumensoConn(true);
        UUID contractId = seedDraftContract();

        wireMock.stubFor(post(urlPathEqualTo("/api/v1/documents"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"documentId\":\"doc_test_idempotent\"}")));

        // First send
        web.post().uri("/contracts/" + contractId + "/send")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isOk();

        // Reset WireMock counters
        wireMock.resetRequests();

        // Second send (different Idempotency-Key to bypass HTTP-layer cache; the
        // domain-level explicit-boolean documensoDocumentId != null check returns
        // the existing contract without calling Documenso again).
        web.post().uri("/contracts/" + contractId + "/send")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SENT")
                .jsonPath("$.documensoDocumentId").isEqualTo("doc_test_idempotent");

        // THE KEY ASSERTION: no second WireMock POST (explicit-boolean idempotency)
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/documents")));
    }
}
