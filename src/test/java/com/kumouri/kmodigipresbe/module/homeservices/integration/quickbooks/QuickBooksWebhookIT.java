package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10d — Intuit inbound webhook with a valid signature → CRM Invoice
 * matched via {@code externalRefs.quickbooks} is marked paid. Tenant isolation:
 * the path-bound tenantId scopes the connection lookup; a webhook signed with
 * tenant B's verifier cannot touch tenant A's invoices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.integrations.quickbooks.enabled=true",
        "kmosf.integrations.quickbooks.client-id=test-cid",
        "kmosf.integrations.quickbooks.client-secret=test-cs",
        "kmosf.integrations.quickbooks.state-signing-secret=test-state-secret"
})
class QuickBooksWebhookIT {

    @Autowired WebTestClient web;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired InvoiceRepository invoices;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String verifier;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Invoice.class).block();

        tenantId = UUID.randomUUID();
        verifier = "tenant-webhook-verifier-secret";
        TenantContext bootstrap = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        connections.save(IntegrationConnection.builder()
                        .tenantId(tenantId)
                        .provider("quickbooks")
                        .secrets(new HashMap<>(Map.of(
                                "accessToken", "a",
                                "refreshToken", "r",
                                "webhookVerifierToken", verifier)))
                        .config(new HashMap<>(Map.of("realmId", "1234567890")))
                        .build())
                .contextWrite(TenantContextHolder.write(bootstrap))
                .block();
    }

    @Test
    void validSignedWebhook_marksMatchedInvoicePaid() {
        // Seed an invoice that has been synced to QBO.
        UUID invoiceId = UUID.randomUUID();
        String qboInvoiceId = "qbo-inv-77";
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        invoices.save(Invoice.builder()
                        .id(invoiceId)
                        .tenantId(tenantId)
                        .invoiceNumber("INV-77")
                        .status(Invoice.Status.SENT)
                        .currency("USD")
                        .total(new BigDecimal("199.00"))
                        .balance(new BigDecimal("199.00"))
                        .issuedAt(LocalDate.now())
                        .dueAt(LocalDate.now().plusDays(30))
                        .externalRefs(new HashMap<>(Map.of("quickbooks", qboInvoiceId)))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        String body = "{\"eventNotifications\":[{"
                + "\"realmId\":\"1234567890\","
                + "\"dataChangeEvent\":{\"entities\":[{"
                + "\"name\":\"Invoice\",\"id\":\"" + qboInvoiceId + "\","
                + "\"operation\":\"Payment\","
                + "\"lastUpdated\":\"2026-05-14T20:00:00-07:00\""
                + "}]}}]}";
        String signature = sign(verifier, body);

        web.post().uri("/public/integrations/quickbooks/" + tenantId + "/webhook")
                .header("intuit-signature", signature)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        // The invoice's status should be PAID after the webhook records the payment.
        Invoice updated = invoices.findById(invoiceId)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo(Invoice.Status.PAID);
    }

    @Test
    void invalidSignature_returns401_errorCode2812() {
        String body = "{\"eventNotifications\":[]}";
        web.post().uri("/public/integrations/quickbooks/" + tenantId + "/webhook")
                .header("intuit-signature", Base64.getEncoder().encodeToString("garbage".getBytes()))
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2812);
    }

    @Test
    void missingSignature_returns401_errorCode2810() {
        String body = "{\"eventNotifications\":[]}";
        web.post().uri("/public/integrations/quickbooks/" + tenantId + "/webhook")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2810);
    }

    @Test
    void unknownTenant_returns404_errorCode2811() {
        UUID unknownTenant = UUID.randomUUID();
        String body = "{\"eventNotifications\":[]}";
        String signature = sign(verifier, body);
        web.post().uri("/public/integrations/quickbooks/" + unknownTenant + "/webhook")
                .header("intuit-signature", signature)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2811);
    }

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
