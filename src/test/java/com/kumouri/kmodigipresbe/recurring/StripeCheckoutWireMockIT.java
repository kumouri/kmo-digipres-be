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
 * AC-E6 — Stripe Checkout, WireMock ONLY (§7 hard no-live-money boundary).
 *
 * <p>{@code kmosf.stripe.api-base-url} is pointed at WireMock via
 * {@code @DynamicPropertySource}. The validator confirms no host is hardcoded and
 * no live key is in any fixture (the apiKey is a sandbox {@code sk_test_}-shaped
 * fake). No request can reach {@code api.stripe.com}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class StripeCheckoutWireMockIT {

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
    static void stripeProps(DynamicPropertyRegistry registry) {
        // The §7 boundary in action: every test points Stripe at WireMock.
        registry.add("kmosf.stripe.api-base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired IntegrationConnectionRepository connections;

    private UUID tenantId;
    private String token;
    private UUID invoiceId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-checkout-" + tenantId)
                .displayName("Checkout Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@checkout.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);

        Invoice inv = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantId)
                .status(Invoice.Status.SENT)
                .currency("USD")
                .lineItems(List.of(LineItem.builder()
                        .description("Consulting")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("400.00"))
                        .lineTotal(new BigDecimal("400.00"))
                        .build()))
                .total(new BigDecimal("400.00"))
                .build()).block();
        invoiceId = inv.getId();
    }

    private void seedStripeConn(boolean withApiKey) {
        Map<String, String> secrets = new HashMap<>();
        if (withApiKey) {
            // Sandbox-shaped fake — NOT a live key (§7).
            secrets.put("apiKey", "sk_test_phaseE_fake_sandbox_key");
        }
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("stripe")
                .secrets(secrets)
                .build()).block();
    }

    @Test
    void checkoutSession_returnsUrl_andWireMockReceivedMetadataInvoiceId() {
        seedStripeConn(true);
        wireMock.stubFor(post(urlPathEqualTo("/v1/checkout/sessions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"cs_test_123\","
                                + "\"url\":\"https://checkout.stripe.com/c/pay/cs_test_123\"}")));

        web.post().uri("/invoices/" + invoiceId + "/stripe-checkout?mode=CHECKOUT_SESSION")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.url").isEqualTo("https://checkout.stripe.com/c/pay/cs_test_123")
                .jsonPath("$.mode").isEqualTo("CHECKOUT_SESSION");

        // WireMock recorded exactly one POST whose form body carried
        // metadata[kmosf_invoice_id]=<invoiceId>. No request targeted api.stripe.com
        // (base URL is WireMock — asserted by the stub being hit at all).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))
                .withRequestBody(containing(
                        "metadata%5Bkmosf_invoice_id%5D=" + invoiceId)));
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
    }

    @Test
    void noApiKey_returns412_3620_noStripeCall() {
        seedStripeConn(false); // connection present but no apiKey
        web.post().uri("/invoices/" + invoiceId + "/stripe-checkout?mode=CHECKOUT_SESSION")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(412)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3620);

        // No outbound Stripe call attempted.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void paymentLinkMode_returnsUrl() {
        seedStripeConn(true);
        wireMock.stubFor(post(urlPathEqualTo("/v1/payment_links"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"plink_123\","
                                + "\"url\":\"https://buy.stripe.com/test_plink_123\"}")));

        web.post().uri("/invoices/" + invoiceId + "/stripe-checkout?mode=PAYMENT_LINK")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.url").isEqualTo("https://buy.stripe.com/test_plink_123")
                .jsonPath("$.mode").isEqualTo("PAYMENT_LINK");

        List<?> events = wireMock.getAllServeEvents();
        assertThat(events).hasSize(1);
    }
}
