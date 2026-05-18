package com.kumouri.kmodigipresbe.integration.activepieces;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscription;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * H.7 — ActivepiecesSeedIT: AC-H3 integration-test proof.
 *
 * <p>Mirrors {@code StripeCheckoutWireMockIT} shape for the outbound WireMock assertion.
 * Static {@code WireMockServer(options().dynamicPort())} + ONE {@code @DynamicPropertySource}
 * pointing the {@code WebhookDeliveryService} at WireMock (via {@code kmosf.webhook-delivery.base-url}
 * — not actually needed because the delivery URL is per-subscription, not a global base URL).
 * WireMock stubs any POST path.
 *
 * <h2>AC-H3 — seed → exactly one WebhookSubscription row (idempotent)</h2>
 * {@code POST /api/v1/integrations/activepieces/seed} → exactly one {@code WebhookSubscription}
 * row. Second call (same tenant+URL+eventType) → no second row ({@code created=false}).
 *
 * <h2>AC-H3 — INVOICE_FINALIZED → WebhookDeliveryService POSTs to Activepieces (WireMock)</h2>
 * After seeding the WireMock URL as the target, publishing {@code INVOICE_FINALIZED} via
 * the unchanged {@code WebhookDeliveryService} triggers a POST to WireMock. We assert the
 * recorded request has the correct event type.
 *
 * <h2>AC-H3 — capability token stored (not admin token)</h2>
 * The stored {@code WebhookSubscription.secret} equals the {@code capabilityToken} passed
 * at seed time (R8 invariant).
 *
 * <p>No {@code @MockBean} — shard-safe. Self-clean {@code mongo.remove} in {@code @BeforeEach}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Activepieces module must be enabled (matchIfMissing=true; explicit for clarity).
        "kmosf.modules.activepieces.enabled=true"
})
class ActivepiecesSeedIT {

    private static final String CAPABILITY_TOKEN = "cap_token_h7_readonly_notadmin";

    // WireMock stubs the Activepieces webhook-trigger endpoint.
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
    static void wireMockProps(DynamicPropertyRegistry registry) {
        // No global base URL for WebhookDeliveryService — the delivery URL is per-subscription.
        // WireMock is here to receive the POST; the seed sets the subscription URL to wireMock.
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private String adminToken;
    private String wireMockUrl;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), WebhookSubscription.class).block();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("ap-seed-" + tenantId)
                .displayName("Activepieces Seed IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        User admin = User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@ap-seed.test")
                .roles(Set.of("ADMIN", "STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        // The seed target URL — points at WireMock so delivery can be verified.
        wireMockUrl = wireMock.baseUrl() + "/ap/webhook/trigger";

        // Stub the WireMock endpoint to accept any POST.
        wireMock.stubFor(post(urlPathEqualTo("/ap/webhook/trigger"))
                .willReturn(aResponse().withStatus(200)));

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    // -------------------------------------------------------------------------
    // AC-H3: seed → exactly one WebhookSubscription row
    // -------------------------------------------------------------------------

    @Test
    void seed_exactlyOneSubscriptionRow_capabilityTokenStoredAsSecret() {
        // First seed call.
        web.post()
                .uri("/api/v1/integrations/activepieces/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of(
                        "targetUrl", wireMockUrl,
                        "capabilityToken", CAPABILITY_TOKEN))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.created").isEqualTo(true);

        // Exactly one WebhookSubscription row.
        List<WebhookSubscription> subs = mongo.findAll(WebhookSubscription.class).collectList().block();
        assertThat(subs)
                .as("AC-H3: exactly one WebhookSubscription must be created")
                .hasSize(1);

        WebhookSubscription sub = subs.get(0);
        assertThat(sub.getUrl())
                .as("AC-H3: subscription URL must match the seed target")
                .isEqualTo(wireMockUrl);
        assertThat(sub.getSecret())
                .as("AC-H3 R8: stored secret must be the capability token (NOT an admin token)")
                .isEqualTo(CAPABILITY_TOKEN);
        assertThat(sub.getEventTypes())
                .as("AC-H3: subscription must include INVOICE_FINALIZED")
                .contains(DomainEventType.INVOICE_FINALIZED);
    }

    // -------------------------------------------------------------------------
    // AC-H3: idempotent — second seed call → no second row
    // -------------------------------------------------------------------------

    @Test
    void seed_idempotent_secondCallNoSecondRow() {
        String body = """
                {"targetUrl":"%s","capabilityToken":"%s"}""".formatted(wireMockUrl, CAPABILITY_TOKEN);

        // First call.
        web.post()
                .uri("/api/v1/integrations/activepieces/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Second call with same params.
        web.post()
                .uri("/api/v1/integrations/activepieces/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.created").isEqualTo(false);

        // Still exactly one row.
        assertThat(mongo.findAll(WebhookSubscription.class).collectList().block())
                .as("AC-H3 idempotent: exactly one WebhookSubscription after second seed call")
                .hasSize(1);
    }

    // -------------------------------------------------------------------------
    // AC-H3: INVOICE_FINALIZED → WebhookDeliveryService POSTs to WireMock
    // -------------------------------------------------------------------------

    @Test
    void invoiceFinalized_webhookDeliveryServicePostsToWireMock() {
        // Seed the subscription pointing at WireMock.
        web.post()
                .uri("/api/v1/integrations/activepieces/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of(
                        "targetUrl", wireMockUrl,
                        "capabilityToken", CAPABILITY_TOKEN))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Confirm the subscription is active.
        List<WebhookSubscription> subs = mongo.findAll(WebhookSubscription.class).collectList().block();
        assertThat(subs).hasSize(1);
        assertThat(subs.get(0).isActive()).isTrue();

        // Create and finalize an invoice so INVOICE_FINALIZED fires.
        Invoice inv = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantId)
                .status(Invoice.Status.SENT)
                .invoiceNumber("INV-2026-0001")
                .currency("USD")
                .lineItems(List.of(LineItem.builder()
                        .description("Service")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("500.00"))
                        .lineTotal(new BigDecimal("500.00"))
                        .build()))
                .total(new BigDecimal("500.00"))
                .build()).block();

        // Manually publish INVOICE_FINALIZED — the event bus triggers WebhookDeliveryService.
        eventPublisher.publish(DomainEvent.of(
                DomainEventType.INVOICE_FINALIZED, tenantId, inv.getId(),
                Map.of("invoiceId", inv.getId().toString())));

        // Awaitility: WireMock must receive the outbound POST within 15s (circuit breaker + retry).
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                wireMock.verify(1, postRequestedFor(urlPathEqualTo("/ap/webhook/trigger"))));

        // Verify the request carried the correct event header.
        var serveEvents = wireMock.getAllServeEvents();
        assertThat(serveEvents).as("AC-H3: exactly one POST must reach WireMock").hasSize(1);
        String eventHeader = serveEvents.get(0).getRequest()
                .header("X-KMOSF-Event").firstValue();
        assertThat(eventHeader)
                .as("AC-H3: X-KMOSF-Event header must be invoice.finalized")
                .isEqualTo(DomainEventType.INVOICE_FINALIZED);
    }
}
