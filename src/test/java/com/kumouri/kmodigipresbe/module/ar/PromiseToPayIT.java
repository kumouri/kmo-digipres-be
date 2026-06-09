package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AR-4 — {@code POST /ar/promises} + {@code GET /ar/promises?invoiceId=} HTTP tests.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@code POST /ar/promises} with a valid body → 200, ACTIVE promise created;</li>
 *   <li>{@code POST /ar/promises} for a missing invoice → 4601;</li>
 *   <li>{@code POST /ar/promises} with a past date → 4602;</li>
 *   <li>{@code POST /ar/promises} with a zero / negative amount → 4602;</li>
 *   <li>{@code GET /ar/promises?invoiceId=} lists the created promise;</li>
 *   <li>{@code GET /ar/promises?invoiceId=} for an invoice with no promises → empty list.</li>
 * </ol>
 *
 * <p>Shard-safe: self-clean {@code mongo.remove} {@code @BeforeEach}; fixed clock for deterministic
 * date validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, PromiseToPayIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.ar.enabled=true",
        "kmosf.modules.ar.initial-delay-ms=3600000",
        "kmosf.modules.ar.interval-ms=3600000"
})
class PromiseToPayIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;
    private UUID invoiceId;

    @BeforeEach
    void seed() {
        wipe();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("ar-promise-it-" + tenantId)
                .displayName("AR Promise IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("ar"))
                .build()).block();

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@ar-promise.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        // One valid invoice for the promise tests.
        invoiceId = UUID.randomUUID();
        mongo.save(Invoice.builder()
                .id(invoiceId).tenantId(tenantId)
                .invoiceNumber("INV-PROMISE-001").status(Invoice.Status.OVERDUE)
                .currency("USD").lineItems(List.of())
                .subtotal(new BigDecimal("500.00"))
                .total(new BigDecimal("500.00"))
                .balance(new BigDecimal("500.00"))
                .issuedAt(TODAY.minusDays(60)).dueAt(TODAY.minusDays(14))
                .build()).block();
    }

    private void wipe() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), PromiseToPay.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();
    }

    // ── 1. Valid promise → 200 ACTIVE ────────────────────────────────────────────────────────

    @Test
    void createPromise_valid_returnsActivePromise() {
        LocalDate futureDate = TODAY.plusDays(7);
        var body = Map.of(
                "invoiceId", invoiceId.toString(),
                "promisedDate", futureDate.toString(),
                "promisedAmount", "250.00",
                "note", "Customer called, promising half payment first"
        );

        PromiseToPay result = web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PromiseToPay.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(result.getStatus()).isEqualTo(PromiseToPay.Status.ACTIVE);
        assertThat(result.getPromisedDate()).isEqualTo(futureDate);
        assertThat(result.getPromisedAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(result.getNote()).isEqualTo("Customer called, promising half payment first");
    }

    @Test
    void createPromise_today_isAllowed() {
        var body = Map.of(
                "invoiceId", invoiceId.toString(),
                "promisedDate", TODAY.toString()  // today is allowed (today-or-future)
        );

        web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PromiseToPay.class)
                .returnResult().getResponseBody();
    }

    // ── 2. Missing invoice → 4601 ────────────────────────────────────────────────────────────

    @Test
    void createPromise_missingInvoice_is4601() {
        var body = Map.of(
                "invoiceId", UUID.randomUUID().toString(),  // non-existent
                "promisedDate", TODAY.plusDays(3).toString()
        );

        web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4601);
    }

    // ── 3. Past date → 4602 ──────────────────────────────────────────────────────────────────

    @Test
    void createPromise_pastDate_is4602() {
        var body = Map.of(
                "invoiceId", invoiceId.toString(),
                "promisedDate", TODAY.minusDays(1).toString()  // yesterday
        );

        web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4602);
    }

    // ── 4. Zero / negative amount → 4602 ─────────────────────────────────────────────────────

    @Test
    void createPromise_zeroAmount_is4602() {
        var body = Map.of(
                "invoiceId", invoiceId.toString(),
                "promisedDate", TODAY.plusDays(5).toString(),
                "promisedAmount", "0"
        );

        web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4602);
    }

    @Test
    void createPromise_negativeAmount_is4602() {
        var body = Map.of(
                "invoiceId", invoiceId.toString(),
                "promisedDate", TODAY.plusDays(5).toString(),
                "promisedAmount", "-10"
        );

        web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4602);
    }

    // ── 5. GET lists the created promise ─────────────────────────────────────────────────────

    @Test
    void listPromises_returnsCreatedPromise() {
        // Create a promise first.
        var body = Map.of(
                "invoiceId", invoiceId.toString(),
                "promisedDate", TODAY.plusDays(7).toString()
        );
        web.post().uri("/ar/promises")
                .header("Authorization", staffToken)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        // List should return it.
        List<PromiseToPay> list = web.get()
                .uri(u -> u.path("/ar/promises").queryParam("invoiceId", invoiceId.toString()).build())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(PromiseToPay.class)
                .returnResult().getResponseBody();

        assertThat(list).isNotNull().hasSize(1);
        assertThat(list.get(0).getInvoiceId()).isEqualTo(invoiceId);
        assertThat(list.get(0).getStatus()).isEqualTo(PromiseToPay.Status.ACTIVE);
    }

    // ── 6. GET for an invoice with no promises → empty list ──────────────────────────────────

    @Test
    void listPromises_noPromises_returnsEmpty() {
        List<PromiseToPay> list = web.get()
                .uri(u -> u.path("/ar/promises").queryParam("invoiceId", invoiceId.toString()).build())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(PromiseToPay.class)
                .returnResult().getResponseBody();

        assertThat(list).isNotNull().isEmpty();
    }
}
