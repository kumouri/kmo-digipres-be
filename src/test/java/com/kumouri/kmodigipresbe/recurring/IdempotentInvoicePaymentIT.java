package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.IdempotencyKeyRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-E4 — {@code @IdempotentRoute} adoption on the money-critical Invoice/Payment
 * POSTs (deferred from Phase A to Phase E — E-D6). Same-key re-POST replays the
 * original 2xx (no duplicate); missing key → 400 {@code 3100}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class IdempotentInvoicePaymentIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IdempotencyKeyRepository idempotencyKeys;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Payment.class).block();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        idempotencyKeys.deleteAll().block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-idemroute-" + tenantId)
                .displayName("Idem Route Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@idemroute.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    private Invoice createInvoice() {
        return web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Invoice.builder()
                        .lineItems(List.of(LineItem.builder()
                                .description("Service")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .discountPercent(BigDecimal.ZERO)
                                .taxPercent(BigDecimal.ZERO)
                                .build()))
                        .build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Invoice.class).returnResult().getResponseBody();
    }

    @Test
    void paymentSameKey_replaysOriginal_oneRow_balanceUnchanged() {
        Invoice inv = createInvoice();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("amount", 100, "method", "CARD");

        byte[] first = web.post().uri("/invoices/" + inv.getId() + "/payments")
                .header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(byte[].class).returnResult().getResponseBody();

        byte[] second = web.post().uri("/invoices/" + inv.getId() + "/payments")
                .header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotency-Replayed", "true")
                .expectBody(byte[].class).returnResult().getResponseBody();

        assertThat(first).isEqualTo(second);

        // Exactly ONE payment row — the replay did NOT record a second payment.
        List<Payment> payments = mongo.findAll(Payment.class).collectList().block();
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void paymentMissingKey_returns400_3100() {
        Invoice inv = createInvoice();
        web.post().uri("/invoices/" + inv.getId() + "/payments")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amount", 50, "method", "CARD"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3100);
    }

    @Test
    void invoiceCreateMissingKey_returns400_3100() {
        web.post().uri("/invoices")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Invoice.builder()
                        .lineItems(List.of(LineItem.builder()
                                .description("X").quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("10.00"))
                                .discountPercent(BigDecimal.ZERO)
                                .taxPercent(BigDecimal.ZERO).build()))
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3100);
    }

    @Test
    void invoiceCreateSameKey_replaysOriginal_oneInvoice() {
        String key = UUID.randomUUID().toString();
        Invoice body = Invoice.builder()
                .lineItems(List.of(LineItem.builder()
                        .description("Service").quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("250.00"))
                        .discountPercent(BigDecimal.ZERO)
                        .taxPercent(BigDecimal.ZERO).build()))
                .build();

        Invoice first = web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Invoice.class).returnResult().getResponseBody();

        web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotency-Replayed", "true");

        List<Invoice> invoices = mongo.findAll(Invoice.class).collectList().block();
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getId()).isEqualTo(first.getId());
    }
}
