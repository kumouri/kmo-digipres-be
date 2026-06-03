package com.kumouri.kmodigipresbe.service.billing;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.quote.Quote;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression spec for the {@code POST /invoices/from-quote/{quoteId}} NPE
 * (reproduced 2026-06-02 while seeding demo data via {@code crm-demo/seed-nmm.mjs}).
 *
 * <p>The bug: {@code createFromQuote} builds an {@link Invoice} in memory and issues
 * it directly as {@code SENT}, so finalize-time numbering
 * ({@code InvoiceService.assignNumberIfIssued} → {@code InvoiceNumberGenerator.next})
 * ran <em>before</em> the invoice was persisted — and a {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped}
 * entity is only tenant-stamped on save. {@code tenantId} was therefore {@code null}
 * inside the number generator → {@code NullPointerException} → 500. The
 * {@code POST /invoices} (DRAFT) → {@code POST /invoices/{id}/status} (finalize) path
 * never hit this because {@code setStatus} loads the invoice from Mongo (tenantId
 * populated) before numbering. The fix stamps the invoice's {@code tenantId} from the
 * source quote (loaded through the tenant-scoped repo, so it equals the context
 * tenant) before numbering. This path had no IT before — which is why the Phase-E
 * numbering work didn't catch it.
 *
 * <p>House style mirrors {@code InvoiceNumberGeneratorIT} verbatim (same
 * {@code @SpringBootTest}/{@code @TestPropertySource} so the Spring context cache is
 * shared): {@code RANDOM_PORT} + {@code @AutoConfigureWebTestClient} +
 * {@code @Import(TestcontainersConfiguration)}; DB asserted via the raw
 * {@link ReactiveMongoTemplate} (the source of truth — it bypasses the auto
 * tenant-filter, the Phase-C/D tenant-scoping-bypass lesson).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class InvoiceFromQuoteIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantAId;
    private UUID tenantBId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Quote.class).block();
        mongo.dropCollection("invoice_number_counters").block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("fq-a-" + tenantAId)
                .displayName("FromQuote Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("fq-b-" + tenantBId)
                .displayName("FromQuote Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@fromquote.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(userA).block();
        tokenA = "Bearer " + jwt.mint(userA);

        User userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@fromquote.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(userB).block();
        tokenB = "Bearer " + jwt.mint(userB);
    }

    /** Create a $100 quote and move it to ACCEPTED — the faithful pre-conversion flow. */
    private Quote createAcceptedQuote(String token, UUID contactId) {
        Quote created = web.post().uri("/quotes")
                .header("Authorization", token)
                .bodyValue(Quote.builder()
                        .contactId(contactId)
                        .lineItems(List.of(LineItem.builder()
                                .description("Consulting")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("100.00"))
                                .discountPercent(BigDecimal.ZERO)
                                .taxPercent(BigDecimal.ZERO)
                                .build()))
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Quote.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();
        // createFromQuote does NOT gate on status, but the documented repro marks the
        // quote ACCEPTED first — mirror it so the IT exercises the real-world flow.
        return web.post().uri("/quotes/" + created.getId() + "/status?target=ACCEPTED")
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Quote.class)
                .returnResult().getResponseBody();
    }

    private Invoice fromQuote(String token, UUID quoteId) {
        return web.post().uri("/invoices/from-quote/" + quoteId)
                .header("Authorization", token)
                .exchange()
                .expectStatus().isCreated() // before the fix: 500 (NPE)
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();
    }

    private Invoice createDraft(String token) {
        return web.post().uri("/invoices")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
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
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();
    }

    private Invoice setStatus(String token, UUID id, Invoice.Status target) {
        return web.post().uri("/invoices/" + id + "/status?target=" + target)
                .header("Authorization", token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Invoice.class)
                .returnResult().getResponseBody();
    }

    /**
     * The headline regression: quote → from-quote returns a numbered, tenant-stamped
     * invoice (201, not 500). Numbering ran with a real tenantId, and the persisted
     * row carries the acting tenant.
     */
    @Test
    void fromQuote_issuesNumberedInvoice_withTenantStamped() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        UUID contactId = UUID.randomUUID();
        Quote quote = createAcceptedQuote(tokenA, contactId);

        Invoice inv = fromQuote(tokenA, quote.getId());

        assertThat(inv).isNotNull();
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.SENT);
        assertThat(inv.getQuoteId()).isEqualTo(quote.getId());
        assertThat(inv.getContactId()).isEqualTo(contactId);
        assertThat(inv.getTotal()).isEqualByComparingTo(new BigDecimal("100.00"));
        // numbered at the issued edge, with a REAL (non-null) tenantId — the bug
        assertThat(inv.getInvoiceNumber()).isEqualTo("INV-" + year + "-0001");

        // tenantId correctly stamped in the DB (raw template = source of truth; proves
        // no null/foreign tenantId reached the number generator or the save).
        Invoice persisted = mongo.findById(inv.getId(), Invoice.class).block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getTenantId()).isEqualTo(tenantAId);
        assertThat(persisted.getInvoiceNumber()).isEqualTo("INV-" + year + "-0001");
    }

    /**
     * Money-path: the from-quote issued-edge and the DRAFT→SENT finalize edge draw
     * from ONE per-(tenant, year) counter — numbers stay gapless and monotonic across
     * both paths (no double-mint, no gap, no collision on the partial-unique
     * {@code tenant_number_idx}).
     */
    @Test
    void fromQuote_andSetStatus_drawFromOneSequentialPerTenantCounter() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String prefix = "INV-" + year + "-";

        Invoice first = fromQuote(tokenA, createAcceptedQuote(tokenA, UUID.randomUUID()).getId());
        assertThat(first.getInvoiceNumber()).isEqualTo(prefix + "0001");

        Invoice draft = createDraft(tokenA);
        assertThat(draft.getInvoiceNumber()).isNull();
        Invoice sent = setStatus(tokenA, draft.getId(), Invoice.Status.SENT);
        assertThat(sent.getInvoiceNumber()).isEqualTo(prefix + "0002");

        Invoice third = fromQuote(tokenA, createAcceptedQuote(tokenA, UUID.randomUUID()).getId());
        assertThat(third.getInvoiceNumber()).isEqualTo(prefix + "0003");
    }

    /**
     * Cross-tenant isolation: each tenant's from-quote draws from its own counter
     * (both start at 0001), and the quote-sourced {@code tenantId} is stamped to the
     * correct tenant on each invoice — no leak across the synthetic-build seam.
     */
    @Test
    void fromQuote_perTenantIndependentCounter_andNoTenantLeak() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String expected = "INV-" + year + "-0001";

        Invoice a = fromQuote(tokenA, createAcceptedQuote(tokenA, UUID.randomUUID()).getId());
        assertThat(a.getInvoiceNumber()).isEqualTo(expected);

        Invoice b = fromQuote(tokenB, createAcceptedQuote(tokenB, UUID.randomUUID()).getId());
        assertThat(b.getInvoiceNumber()).isEqualTo(expected);

        Invoice persistedA = mongo.findById(a.getId(), Invoice.class).block();
        Invoice persistedB = mongo.findById(b.getId(), Invoice.class).block();
        assertThat(persistedA).isNotNull();
        assertThat(persistedB).isNotNull();
        assertThat(persistedA.getTenantId()).isEqualTo(tenantAId);
        assertThat(persistedB.getTenantId()).isEqualTo(tenantBId);
    }
}
