package com.kumouri.kmodigipresbe.recurring;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.awaitility.Awaitility;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-E7 — debug accounting-push round-trips an invoice into a QuickBooks sandbox
 * (WireMock) via the SHIPPED {@code INVOICE_FINALIZED → QuickBooksInvoiceSync}
 * seam (E-D10). {@code QuickBooksInvoiceSync} is NOT modified — this only adds
 * the debug endpoint + this WireMock IT. No live Intuit (§7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Enable the SHIPPED QBO auto-config so QuickBooksInvoiceSync subscribes.
        "kmosf.integrations.quickbooks.enabled=true"
})
class AccountingPushRoundTripIT {

    private static final String REALM_ID = "9341452346000000";

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
    static void qboProps(DynamicPropertyRegistry registry) {
        // Point the SHIPPED QBO sync's API + OAuth base URLs at WireMock — never
        // Intuit (§7).
        registry.add("kmosf.integrations.quickbooks.api-base-url", () -> wireMock.baseUrl());
        registry.add("kmosf.integrations.quickbooks.oauth-base-url", () -> wireMock.baseUrl());
        registry.add("kmosf.integrations.quickbooks.client-id", () -> "test-client");
        registry.add("kmosf.integrations.quickbooks.client-secret", () -> "test-secret");
        registry.add("kmosf.integrations.quickbooks.state-signing-secret", () -> "test-state");
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired IntegrationConnectionRepository connections;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;
    private UUID invoiceId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-acct-" + tenantId)
                .displayName("Accounting Push Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@acct.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@acct.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        // QBO connection with a non-expired token so refreshIfNeeded skips the
        // refresh HTTP call.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("quickbooks")
                .secrets(new HashMap<>(Map.of(
                        "accessToken", "valid-access-token",
                        "refreshToken", "refresh-token",
                        "tokenExpiresAt", Instant.now().plusSeconds(3600).toString())))
                .config(new HashMap<>(Map.of("realmId", REALM_ID)))
                .build()).block();

        Invoice inv = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantId)
                .status(Invoice.Status.DRAFT)
                .currency("USD")
                .lineItems(List.of(LineItem.builder()
                        .description("Service call")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("125.00"))
                        .lineTotal(new BigDecimal("125.00"))
                        .build()))
                .total(new BigDecimal("125.00"))
                .externalRefs(new HashMap<>())
                .build()).block();
        invoiceId = inv.getId();
    }

    @Test
    void adminAccountingPush_draftToSent_roundTripsIntoQboSandbox_andStampsExternalRef() {
        wireMock.stubFor(post(urlPathEqualTo("/v3/company/" + REALM_ID + "/invoice"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Invoice\":{\"Id\":\"qbo-inv-99\"}}")));

        web.post().uri("/invoices/" + invoiceId + "/accounting-push")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().is2xxSuccessful();

        // The shipped QuickBooksInvoiceSync subscribed to INVOICE_FINALIZED and
        // pushed to WireMock; it stamps externalRefs["quickbooks"].
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                wireMock.verify(postRequestedFor(
                        urlPathEqualTo("/v3/company/" + REALM_ID + "/invoice"))));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            // mongo.findById bypasses tenant scoping (no JUnit-thread context).
            Invoice after = mongo.findById(invoiceId, Invoice.class).block();
            assertThat(after).isNotNull();
            assertThat(after.getStatus()).isEqualTo(Invoice.Status.SENT);
            assertThat(after.getExternalRefs()).containsEntry("quickbooks", "qbo-inv-99");
        });

        // Re-invoke = no-op (QuickBooksInvoiceSync.alreadySynced via
        // externalRefs["quickbooks"]). The invoice is already SENT so the service
        // re-publishes INVOICE_FINALIZED, but the sync skips the already-synced
        // invoice — no second QBO POST.
        int before = wireMock.getAllServeEvents().size();
        web.post().uri("/invoices/" + invoiceId + "/accounting-push")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().is2xxSuccessful();
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // No additional QBO POST (idempotent via externalRefs["quickbooks"]).
        assertThat(wireMock.getAllServeEvents().size()).isEqualTo(before);
    }

    @Test
    void nonAdminAccountingPush_returns403_1800() {
        web.post().uri("/invoices/" + invoiceId + "/accounting-push")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }
}
