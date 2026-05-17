package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-G4 (write-isolation) — portal quote accept/decline.
 *
 * <p>Seeds Tenant A (contactA w/ companyA, contactA2 w/o company) and
 * Tenant B (contactB). Proves:
 * <ul>
 *   <li>SENT → ACCEPTED: quote status updated, PORTAL_QUOTE_ACCEPTED emitted</li>
 *   <li>SENT → DECLINED: quote status updated, PORTAL_QUOTE_DECLINED emitted</li>
 *   <li>Non-SENT → accept: 3805/409</li>
 *   <li>Another contact's quote (same tenant) → 3802 same-404 AND status unchanged</li>
 *   <li>Tenant B quote with Tenant A token → 3802 unchanged</li>
 *   <li>Portal-accept does NOT auto-spawn a Contract (mongo.findAll(Contract.class) empty)</li>
 * </ul>
 * Shard-safe: no @MockBean, self-clean @BeforeEach, independent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalQuotesAcceptDeclineIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher events;

    private UUID tenantAId;
    private UUID tenantBId;
    private User userA;
    private User userANoCompany;
    private User userB;
    private Contact contactA;
    private Contact contactA2;
    private UUID companyA;

    private Quote sentQuoteA;          // SENT, owned by contactA — accept/decline target
    private Quote draftQuoteA;         // DRAFT, owned by contactA — non-SENT guard
    private Quote sentQuoteA2;         // SENT, owned by contactA2 — cross-contact isolation
    private Quote sentQuoteB;          // SENT, Tenant B — cross-tenant isolation

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), Quote.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("q-a-" + tenantAId)
                .displayName("Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("q-b-" + tenantBId)
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
                .email("a@q.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();

        userANoCompany = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a2@q.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA2.getId()).build();
        users.save(userANoCompany).block();

        userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@q.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId()).build();
        users.save(userB).block();

        sentQuoteA = mongo.save(Quote.builder()
                .id(UUID.randomUUID()).tenantId(tenantAId)
                .status(Quote.Status.SENT)
                .contactId(contactA.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(BigDecimal.valueOf(500))
                .build()).block();

        draftQuoteA = mongo.save(Quote.builder()
                .id(UUID.randomUUID()).tenantId(tenantAId)
                .status(Quote.Status.DRAFT)
                .contactId(contactA.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(BigDecimal.valueOf(200))
                .build()).block();

        sentQuoteA2 = mongo.save(Quote.builder()
                .id(UUID.randomUUID()).tenantId(tenantAId)
                .status(Quote.Status.SENT)
                .contactId(contactA2.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(BigDecimal.valueOf(300))
                .build()).block();

        sentQuoteB = mongo.save(Quote.builder()
                .id(UUID.randomUUID()).tenantId(tenantBId)
                .status(Quote.Status.SENT)
                .contactId(contactB.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(BigDecimal.valueOf(400))
                .build()).block();
    }

    // ─── Accept tests ─────────────────────────────────────────────────────────

    @Test
    void acceptSentQuote_updatesStatus_andEmitsEvent() {
        List<DomainEvent> captured = new CopyOnWriteArrayList<>();
        Disposable sub = events.stream()
                .filter(e -> DomainEventType.PORTAL_QUOTE_ACCEPTED.equals(e.type()))
                .subscribe(captured::add);

        try {
            String token = jwt.mint(userA);
            web.post().uri("/portal/me/quotes/" + sentQuoteA.getId() + "/accept")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("ACCEPTED");

            // Wait for advisory event
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !captured.isEmpty());
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).type()).isEqualTo(DomainEventType.PORTAL_QUOTE_ACCEPTED);

            // Verify no Contract was spawned (portal-accept does NOT auto-spawn — G-D5)
            long contractCount = mongo.findAll(Contract.class).count().block();
            assertThat(contractCount).isZero();

            // DB verify: status is ACCEPTED
            Quote updated = mongo.findById(sentQuoteA.getId(), Quote.class).block();
            assertThat(updated).isNotNull();
            assertThat(updated.getStatus()).isEqualTo(Quote.Status.ACCEPTED);
        } finally {
            sub.dispose();
        }
    }

    // ─── Decline tests ────────────────────────────────────────────────────────

    @Test
    void declineSentQuote_updatesStatus_andEmitsEvent() {
        List<DomainEvent> captured = new CopyOnWriteArrayList<>();
        Disposable sub = events.stream()
                .filter(e -> DomainEventType.PORTAL_QUOTE_DECLINED.equals(e.type()))
                .subscribe(captured::add);

        try {
            // Use sentQuoteA2 context: need a fresh SENT quote for contactA
            // Re-use sentQuoteA — seed a fresh one inline
            Quote freshSent = mongo.save(Quote.builder()
                    .id(UUID.randomUUID()).tenantId(tenantAId)
                    .status(Quote.Status.SENT)
                    .contactId(contactA.getId())
                    .currency("USD")
                    .lineItems(List.of())
                    .total(BigDecimal.valueOf(600))
                    .build()).block();

            String token = jwt.mint(userA);
            web.post().uri("/portal/me/quotes/" + freshSent.getId() + "/decline")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("DECLINED");

            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !captured.isEmpty());
            assertThat(captured.get(0).type()).isEqualTo(DomainEventType.PORTAL_QUOTE_DECLINED);

            Quote updated = mongo.findById(freshSent.getId(), Quote.class).block();
            assertThat(updated).isNotNull();
            assertThat(updated.getStatus()).isEqualTo(Quote.Status.DECLINED);
        } finally {
            sub.dispose();
        }
    }

    // ─── Non-SENT guard ───────────────────────────────────────────────────────

    @Test
    void acceptDraftQuote_returns3805() {
        String token = jwt.mint(userA);
        web.post().uri("/portal/me/quotes/" + draftQuoteA.getId() + "/accept")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3805);

        // Status unchanged
        Quote unchanged = mongo.findById(draftQuoteA.getId(), Quote.class).block();
        assertThat(unchanged).isNotNull();
        assertThat(unchanged.getStatus()).isEqualTo(Quote.Status.DRAFT);
    }

    // ─── Cross-contact isolation (same tenant) ────────────────────────────────

    @Test
    void anotherContactQuote_accept_returns3802_andStatusUnchanged() {
        // userA tries to accept sentQuoteA2 (owned by contactA2, same tenant)
        String token = jwt.mint(userA);
        web.post().uri("/portal/me/quotes/" + sentQuoteA2.getId() + "/accept")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3802);

        // Cross-contact write-isolation proof: status must be unchanged
        Quote unchanged = mongo.findById(sentQuoteA2.getId(), Quote.class).block();
        assertThat(unchanged).isNotNull();
        assertThat(unchanged.getStatus()).isEqualTo(Quote.Status.SENT);
    }

    // ─── Cross-tenant isolation ───────────────────────────────────────────────

    @Test
    void tenantBQuote_withTenantAToken_returns3802_andStatusUnchanged() {
        String token = jwt.mint(userA);
        web.post().uri("/portal/me/quotes/" + sentQuoteB.getId() + "/accept")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3802);

        Quote unchanged = mongo.findById(sentQuoteB.getId(), Quote.class).block();
        assertThat(unchanged).isNotNull();
        assertThat(unchanged.getStatus()).isEqualTo(Quote.Status.SENT);
    }
}
