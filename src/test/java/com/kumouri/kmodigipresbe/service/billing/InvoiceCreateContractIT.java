package com.kumouri.kmodigipresbe.service.billing;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract spec for {@code POST /invoices} — {@code create} is the DRAFT-creation
 * seam (it deliberately neither numbers nor emits {@code INVOICE_FINALIZED}; only the
 * DRAFT→issued transition does). A client that POSTs a born-issued status
 * (e.g. {@code SENT}) must be rejected rather than persisted as an
 * issued-but-unnumbered/un-finalized invoice — error {@code 2301}/400. This was the
 * sibling gap to the {@code createFromQuote} numbering bug: both create paths must
 * uphold "issued ⇒ numbered". Issuing is done via {@code POST /invoices/{id}/status}.
 *
 * <p>House style mirrors {@code InvoiceFromQuoteIT} / {@code InvoiceNumberGeneratorIT}
 * verbatim (same {@code @SpringBootTest}/{@code @TestPropertySource} → shared Spring
 * context cache). {@code POST /invoices} is {@code @IdempotentRoute}, so every create
 * carries an {@code Idempotency-Key} (a missing key is its own 3100 error, checked by
 * the filter before this guard is even reached).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class InvoiceCreateContractIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.dropCollection("invoice_number_counters").block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("inv-create-" + tenantId)
                .displayName("InvoiceCreate Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("create@inv.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    private List<LineItem> oneLine() {
        return List.of(LineItem.builder()
                .description("Service")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .discountPercent(BigDecimal.ZERO)
                .taxPercent(BigDecimal.ZERO)
                .build());
    }

    private long invoiceCount() {
        return mongo.count(new Query(), Invoice.class).block();
    }

    /**
     * The guard: POSTing a non-DRAFT status is rejected with 2301/400 and persists
     * nothing — no issued-but-unnumbered invoice can be created via this seam.
     */
    @Test
    void createWithIssuedStatus_isRejected_2301_andPersistsNothing() {
        web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Invoice.builder()
                        .status(Invoice.Status.SENT)
                        .lineItems(oneLine())
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(2301);

        assertThat(invoiceCount()).isZero();
    }

    /** An explicit DRAFT create still succeeds as an unnumbered DRAFT (the happy path). */
    @Test
    void createWithExplicitDraft_succeeds_asUnnumberedDraft() {
        Invoice created = web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Invoice.builder()
                        .status(Invoice.Status.DRAFT)
                        .lineItems(oneLine())
                        .build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(Invoice.Status.DRAFT);
        assertThat(created.getInvoiceNumber()).isNull(); // DRAFTs are never numbered
        assertThat(invoiceCount()).isEqualTo(1);
    }

    /**
     * A status-less POST (the common client case) is normalized to DRAFT — proving
     * the null→DRAFT normalization, independent of the Lombok {@code @Builder.Default}
     * /Jackson no-args interaction. Sent as a raw map so no {@code status} key is on
     * the wire.
     */
    @Test
    void createWithNoStatus_isNormalizedToDraft() {
        Invoice created = web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(Map.of("lineItems", List.of(Map.of(
                        "description", "Service",
                        "quantity", 1,
                        "unitPrice", new BigDecimal("100.00"),
                        "discountPercent", 0,
                        "taxPercent", 0))))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(Invoice.Status.DRAFT);
        assertThat(created.getInvoiceNumber()).isNull();
    }
}
