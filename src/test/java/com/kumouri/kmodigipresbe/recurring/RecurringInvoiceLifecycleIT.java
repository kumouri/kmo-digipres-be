package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-E1 — RecurringInvoice lifecycle + RRULE validation.
 *
 * <p>House style: {@code @SpringBootTest(RANDOM_PORT)} + {@code @AutoConfigureWebTestClient}
 * + {@code @Import(TestcontainersConfiguration.class)} +
 * {@code kmosf.quartz.proof-job.enabled=false} +
 * {@code kmosf.recurring-invoice.spawn-job.enabled=false} (drive spawn
 * deterministically — no background tick). WebTestClient applies the {@code /api/v1}
 * base path automatically. DB asserted via {@code mongo.findAll} (the Phase-C/D
 * ReactiveMongoTemplate-bypasses-tenant-scoping lesson).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class RecurringInvoiceLifecycleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), RecurringInvoice.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-life-" + tenantId)
                .displayName("RI Lifecycle Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@ri.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    private RecurringInvoice template(String rrule, Instant seedAt) {
        return RecurringInvoice.builder()
                .templateName("Monthly Retainer")
                .rrule(rrule)
                .seedAt(seedAt)
                .paymentTerms(com.kumouri.kmodigipresbe.model.billing.Invoice.PaymentTerms.NET_30)
                .lineItems(List.of(LineItem.builder()
                        .description("Retainer")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("500.00"))
                        .discountPercent(BigDecimal.ZERO)
                        .taxPercent(BigDecimal.ZERO)
                        .build()))
                .build();
    }

    @Test
    void createMonthlyRecurringInvoice_returns201ActiveWithNextRun() {
        // seed in the (near) future so the first occurrence is >= now (AC-E1).
        Instant seedAt = Instant.now().plus(2, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        RecurringInvoice created = web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(template("FREQ=MONTHLY;BYMONTHDAY=15", seedAt))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RecurringInvoice.class).returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(RecurringInvoice.Status.ACTIVE);
        assertThat(created.getNextRunAt()).isNotNull();
        // First occurrence must be >= now.
        assertThat(created.getNextRunAt()).isAfterOrEqualTo(
                Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(created.getOccurrenceCount()).isZero();
        assertThat(created.getPaymentTerms())
                .isEqualTo(com.kumouri.kmodigipresbe.model.billing.Invoice.PaymentTerms.NET_30);
    }

    @Test
    void blankRrule_returns400_3603() {
        RecurringInvoice body = template("FREQ=MONTHLY", Instant.now());
        body.setRrule("  ");
        web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3603);
    }

    @Test
    void emptyLineItems_returns400_3602() {
        RecurringInvoice body = template("FREQ=MONTHLY", Instant.now());
        body.setLineItems(List.of());
        web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3602);
    }

    @Test
    void malformedRrule_returns400_1300_reusedCode() {
        RecurringInvoice body = template("THIS-IS-NOT-A-RRULE", Instant.now());
        web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                // E-D11: a malformed RRULE reuses the existing 1300 (owned by
                // Rfc5545RecurringSchedule), NOT a new Phase-E code.
                .jsonPath("$.errorCode").isEqualTo(1300);
    }

    @Test
    void blankTemplateName_returns400_3601() {
        RecurringInvoice body = template("FREQ=MONTHLY", Instant.now());
        body.setTemplateName(" ");
        web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3601);
    }

    @Test
    void pauseThenIllegalEndedToActive_returns409_3606() {
        Instant seedAt = Instant.now().plus(1, ChronoUnit.DAYS);
        RecurringInvoice created = web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(template("FREQ=MONTHLY;BYMONTHDAY=15", seedAt))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RecurringInvoice.class).returnResult().getResponseBody();

        // ACTIVE -> PAUSED ok
        web.post().uri("/recurring-invoices/" + created.getId() + "/status?status=PAUSED")
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PAUSED");

        // PAUSED -> ENDED ok
        web.post().uri("/recurring-invoices/" + created.getId() + "/status?status=ENDED")
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ENDED");

        // ENDED -> ACTIVE illegal -> 409 3606
        web.post().uri("/recurring-invoices/" + created.getId() + "/status?status=ACTIVE")
                .header("Authorization", token)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3606);

        // And modifying an ENDED template -> 409 3607
        RecurringInvoice patch = template("FREQ=MONTHLY;BYMONTHDAY=1", seedAt);
        web.put().uri("/recurring-invoices/" + created.getId())
                .header("Authorization", token)
                .bodyValue(patch)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3607);
    }
}
