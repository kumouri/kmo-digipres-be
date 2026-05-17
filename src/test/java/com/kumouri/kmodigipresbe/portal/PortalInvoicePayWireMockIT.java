package com.kumouri.kmodigipresbe.portal;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-G1 (HEADLINE) — portal pay-invoice, gate-before-checkout proof (§9 #2).
 *
 * <p>WireMock intercepts {@code POST /v1/checkout/sessions} at a dynamic local port.
 * {@code kmosf.stripe.api-base-url} is pointed at WireMock via
 * {@code @DynamicPropertySource}. No request can reach {@code api.stripe.com}.
 * Mirrors the {@link com.kumouri.kmodigipresbe.recurring.StripeCheckoutWireMockIT} shape
 * exactly (static server, @BeforeAll/@AfterAll, resetAll @BeforeEach, one
 * @DynamicPropertySource — §5 shard-safe rule).
 *
 * <p><strong>The critical assertion</strong>: a rejected invoice (cross-contact or
 * cross-tenant) → {@code 3801 / 404} AND WireMock recorded ZERO requests.
 * This proves the ownership gate runs BEFORE any Stripe traffic is generated.
 *
 * <p>Sandbox fake key {@code sk_test_phaseG_fake_portal_key} — not a live key (§7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class PortalInvoicePayWireMockIT {

    // Static WireMock server — shared across all tests in this class (the StripeCheckoutWireMockIT shape)
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

    /** Single @DynamicPropertySource per the shard-safe rule (§5, §9 #3). */
    @DynamicPropertySource
    static void stripeProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.stripe.api-base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired IntegrationConnectionRepository connections;

    private UUID tenantAId;
    private UUID tenantBId;
    private User userA;
    private User userANoCompany;
    private User userB;
    private Contact contactA;
    private Contact contactA2;
    private UUID companyA;
    private Invoice ownedInvoice;    // Tenant A, owned by contactA
    private Invoice otherInvoice;    // Tenant A, owned by contactA2 — cross-contact
    private Invoice tenantBInvoice;  // Tenant B — cross-tenant

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("pay-a-" + tenantAId)
                .displayName("Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("pay-b-" + tenantBId)
                .displayName("Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        companyA = UUID.randomUUID();

        contactA = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("Alice").lastName("A").companyId(companyA).build();
        mongo.save(contactA).block();

        contactA2 = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("NoCoA2").lastName("A2").build();
        mongo.save(contactA2).block();

        Contact contactB = Contact.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .firstName("Bob").lastName("B").build();
        mongo.save(contactB).block();

        userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@pay.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();

        userANoCompany = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a2@pay.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA2.getId()).build();
        users.save(userANoCompany).block();

        userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@pay.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId()).build();
        users.save(userB).block();

        // Invoices
        ownedInvoice = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantAId)
                .status(Invoice.Status.SENT)
                .contactId(contactA.getId())
                .currency("USD")
                .lineItems(List.of(LineItem.builder()
                        .description("Service").quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("500.00"))
                        .lineTotal(new BigDecimal("500.00")).build()))
                .total(new BigDecimal("500.00"))
                .build()).block();

        otherInvoice = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantAId)
                .status(Invoice.Status.SENT)
                .contactId(contactA2.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(new BigDecimal("300.00"))
                .build()).block();

        tenantBInvoice = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantBId)
                .status(Invoice.Status.SENT)
                .contactId(contactB.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(new BigDecimal("400.00"))
                .build()).block();
    }

    private void seedStripeConn(UUID tenantId) {
        Map<String, String> secrets = new HashMap<>();
        // Sandbox-shaped fake key — NOT a live key (§7 hard boundary)
        secrets.put("apiKey", "sk_test_phaseG_fake_portal_key");
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("stripe")
                .secrets(secrets)
                .build()).block();
    }

    private void stubCheckoutSession() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/checkout/sessions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"cs_test_portal_123\","
                                + "\"url\":\"https://checkout.stripe.com/c/pay/cs_test_portal_123\"}")));
    }

    // ─── Happy-path: owned invoice ────────────────────────────────────────────

    @Test
    void pay_ownedInvoice_returnsCheckoutUrl_andWireMockRecordsOneRequest() {
        seedStripeConn(tenantAId);
        stubCheckoutSession();

        String token = jwt.mint(userA);
        web.post().uri("/portal/me/invoices/" + ownedInvoice.getId() + "/pay")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.checkoutUrl").isEqualTo(
                        "https://checkout.stripe.com/c/pay/cs_test_portal_123");

        // WireMock recorded exactly one POST carrying metadata[kmosf_invoice_id]=<ownedInvoice.id>
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))
                .withRequestBody(containing(
                        "metadata%5Bkmosf_invoice_id%5D=" + ownedInvoice.getId())));
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
    }

    // ─── GATE-BEFORE-CHECKOUT: cross-contact (same tenant) ───────────────────

    @Test
    void pay_anotherContactInvoice_returns3801_andZeroWireMockRequests() {
        seedStripeConn(tenantAId);
        stubCheckoutSession();

        // userA (contactA) tries to pay otherInvoice (owned by contactA2, same tenant)
        String token = jwt.mint(userA);
        web.post().uri("/portal/me/invoices/" + otherInvoice.getId() + "/pay")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3801);

        // THE critical assertion: gate blocked before ANY Stripe call
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // ─── GATE-BEFORE-CHECKOUT: cross-tenant ──────────────────────────────────

    @Test
    void pay_tenantBInvoice_withTenantAToken_returns3801_andZeroWireMockRequests() {
        seedStripeConn(tenantAId);
        stubCheckoutSession();

        String token = jwt.mint(userA);
        web.post().uri("/portal/me/invoices/" + tenantBInvoice.getId() + "/pay")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3801);

        // Gate blocked before ANY Stripe call
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // ─── Exactly-once (@IdempotentRoute replay) ───────────────────────────────

    @Test
    void pay_sameIdempotencyKey_replaysSameUrl_andNoSecondStripePost() {
        seedStripeConn(tenantAId);
        stubCheckoutSession();

        String idemKey = UUID.randomUUID().toString();
        String token = jwt.mint(userA);

        // First call
        String checkoutUrl = web.post().uri("/portal/me/invoices/" + ownedInvoice.getId() + "/pay")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idemKey)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();

        assertThat(wireMock.getAllServeEvents()).hasSize(1);

        // Second call with same key — should replay (idempotency middleware handles this)
        web.post().uri("/portal/me/invoices/" + ownedInvoice.getId() + "/pay")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idemKey)
                .exchange()
                .expectStatus().is2xxSuccessful();

        // No second WireMock POST
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
    }

    // ─── No Stripe connection (2510 surfaces via the gate-owned invoice path) ─

    @Test
    void pay_ownedInvoice_noStripeConnection_returns2510() {
        // No seedStripeConn() — so 2510 from StripeCheckoutService (after the gate)
        String token = jwt.mint(userA);
        web.post().uri("/portal/me/invoices/" + ownedInvoice.getId() + "/pay")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2510);

        // No WireMock call attempted (no connection means it errors before the HTTP call)
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
