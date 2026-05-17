package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.model.billing.StripeWebhookEvent;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-E5 — signed Stripe webhook → PAID + Payment + INVOICE_PAID, idempotent on
 * the Stripe EVENT id (E-D7 — closes the biggest pre-existing money gap).
 *
 * <p><strong>No live Stripe (§7):</strong> the signature is computed locally with
 * a TEST signing secret via the shipped {@code StripeSignatureVerifier} scheme
 * ({@code HMAC-SHA256(t + "." + body)}). No request leaves the JVM; nothing can
 * initiate a real charge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class StripeWebhookIdempotencyIT {

    private static final String TEST_SIGNING_SECRET = "whsec_test_phaseE_supersecret";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private UUID invoiceId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Payment.class).block();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), StripeWebhookEvent.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-stripe-" + tenantId)
                .displayName("Stripe Webhook Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("stripe")
                .secrets(new HashMap<>(Map.of(
                        "webhookSigningSecret", TEST_SIGNING_SECRET)))
                .build()).block();

        Invoice inv = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantId)
                .status(Invoice.Status.SENT)
                .currency("USD")
                .lineItems(List.of(LineItem.builder()
                        .description("Service")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("125.00"))
                        .lineTotal(new BigDecimal("125.00"))
                        .build()))
                .subtotal(new BigDecimal("125.00"))
                .total(new BigDecimal("125.00"))
                .build()).block();
        invoiceId = inv.getId();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private String body(String eventId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"pi_test_123\","
                + "\"amount_received\":12500,\"currency\":\"usd\","
                + "\"metadata\":{\"kmosf_invoice_id\":\"" + invoiceId + "\"}}}}";
    }

    private String sign(String body) {
        long t = System.currentTimeMillis() / 1000L;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    TEST_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = HexFormat.of().formatHex(
                    mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
            return "t=" + t + ",v1=" + sig;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void postWebhook(String body, String signature, int expectedStatus) {
        web.post().uri("/public/integrations/stripe/" + tenantId + "/webhook")
                .header("Stripe-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
    }

    @Test
    void signedWebhook_recordsPayment_marksPaid_emitsInvoicePaid_idempotentOnEventId() {
        String eventId = "evt_phaseE_" + UUID.randomUUID();
        String b = body(eventId);

        // First delivery — 200, one Payment, invoice PAID, INVOICE_PAID emitted.
        postWebhook(b, sign(b), 200);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Payment> payments = mongo.findAll(Payment.class).collectList().block();
            assertThat(payments).hasSize(1);
        });
        List<Payment> payments = mongo.findAll(Payment.class).collectList().block();
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo("125.00");
        assertThat(payments.get(0).getMethod()).isEqualTo(Payment.Method.CARD);

        // Read via ReactiveMongoTemplate (bypasses tenant scoping) — the Phase-C/D
        // lesson: a tenant-scoped repo findById requires a Reactor context absent
        // in a context-less JUnit thread.
        Invoice paid = mongo.findById(invoiceId, Invoice.class).block();
        assertThat(paid.getStatus()).isEqualTo(Invoice.Status.PAID);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.INVOICE_PAID.equals(e.type())
                                && "stripe".equals(e.payload().get("source"))));

        // Exactly one ledger row for the event.
        List<StripeWebhookEvent> ledger =
                mongo.findAll(StripeWebhookEvent.class).collectList().block();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getStripeEventId()).isEqualTo(eventId);

        // RE-DELIVER the SAME event id (re-signed; Stripe re-delivery sim) → 200
        // no-op: still exactly ONE Payment, still PAID, still ONE ledger row.
        postWebhook(b, sign(b), 200);
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertThat(mongo.findAll(Payment.class).collectList().block()).hasSize(1);
        assertThat(mongo.findAll(StripeWebhookEvent.class).collectList().block()).hasSize(1);
        assertThat(mongo.findById(invoiceId, Invoice.class).block().getStatus())
                .isEqualTo(Invoice.Status.PAID);
    }

    @Test
    void invalidSignature_returns401_2512_noPayment() {
        String eventId = "evt_phaseE_badsig_" + UUID.randomUUID();
        String b = body(eventId);
        postWebhook(b, "t=" + (System.currentTimeMillis() / 1000L) + ",v1=deadbeef", 401);

        // Defensive: a 401 must not have recorded anything.
        assertThat(mongo.findAll(Payment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(StripeWebhookEvent.class).collectList().block()).isEmpty();
    }
}
