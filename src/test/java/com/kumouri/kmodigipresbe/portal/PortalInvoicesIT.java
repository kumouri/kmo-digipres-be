package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalInvoicesIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantAId;
    private UUID tenantBId;
    private User userA;
    private User userB;
    private Contact contactA; // has companyId
    private Contact contactNoCompany;
    private UUID companyA;
    private User userANoCompany;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("inv-a-" + tenantAId)
                .displayName("Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("inv-b-" + tenantBId)
                .displayName("Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        companyA = UUID.randomUUID();
        contactA = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("Alice").lastName("A").companyId(companyA).build();
        mongo.save(contactA).block();

        contactNoCompany = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("NoCo").lastName("Contact").build();
        mongo.save(contactNoCompany).block();

        Contact contactB = Contact.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .firstName("Bob").lastName("B").build();
        mongo.save(contactB).block();

        userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@a.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();

        userANoCompany = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("noco@a.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactNoCompany.getId()).build();
        users.save(userANoCompany).block();

        userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@b.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId()).build();
        users.save(userB).block();

        // Tenant A invoices: one via contactId (DRAFT), one via companyId (PAID), one unrelated
        mongo.save(invoice(tenantAId, "INV-A-1", Invoice.Status.DRAFT,
                contactA.getId(), null, LocalDate.of(2026, 3, 1))).block();
        mongo.save(invoice(tenantAId, "INV-A-2", Invoice.Status.PAID,
                null, companyA, LocalDate.of(2026, 4, 1))).block();
        mongo.save(invoice(tenantAId, "INV-A-3", Invoice.Status.SENT,
                UUID.randomUUID(), null, LocalDate.of(2026, 5, 1))).block();

        // Tenant A invoice tied to the no-company contact
        mongo.save(invoice(tenantAId, "INV-A-NC-1", Invoice.Status.SENT,
                contactNoCompany.getId(), null, LocalDate.of(2026, 2, 1))).block();

        // Tenant B invoice tied to user B's contact — must never leak to user A
        mongo.save(invoice(tenantBId, "INV-B-1", Invoice.Status.PAID,
                contactB.getId(), null, LocalDate.of(2026, 4, 15))).block();
    }

    private static Invoice invoice(UUID tenantId, String number, Invoice.Status status,
                                   UUID contactId, UUID companyId, LocalDate issued) {
        return Invoice.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .invoiceNumber(number)
                .status(status)
                .contactId(contactId)
                .companyId(companyId)
                .currency("USD")
                .total(new BigDecimal("100.00"))
                .balance(new BigDecimal("100.00"))
                .issuedAt(issued)
                .lineItems(List.of())
                .build();
    }

    @Test
    void unauthenticatedRejected() {
        web.get().uri("/portal/me/invoices").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void listsContactOrCompanyMatches() {
        String token = jwt.mint(userA);

        web.get().uri("/portal/me/invoices")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(java.util.Map.class)
                .hasSize(2);
    }

    @Test
    void filtersByStatus() {
        String token = jwt.mint(userA);

        web.get().uri("/portal/me/invoices?status=DRAFT")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].invoiceNumber").isEqualTo("INV-A-1")
                .jsonPath("$[0].status").isEqualTo("DRAFT")
                .jsonPath("$[0].tenantId").doesNotExist()
                .jsonPath("$[0].contactId").doesNotExist();
    }

    @Test
    void contactWithNoCompanyOnlyMatchesContactId() {
        String token = jwt.mint(userANoCompany);

        web.get().uri("/portal/me/invoices")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].invoiceNumber").isEqualTo("INV-A-NC-1");
    }

    @Test
    void crossTenantIsolation() {
        String tokenB = jwt.mint(userB);

        web.get().uri("/portal/me/invoices")
                .header("Authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].invoiceNumber").isEqualTo("INV-B-1");
    }

    @Test
    void userWithNullContactIdReturns404() {
        User unlinked = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("u@a.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(null).build();
        users.save(unlinked).block();
        String token = jwt.mint(unlinked);

        web.get().uri("/portal/me/invoices")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1252);
    }

    @Test
    void invalidStatusEnumReturnsBadRequest() {
        String token = jwt.mint(userA);

        web.get().uri("/portal/me/invoices?status=NOT_A_STATUS")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().is4xxClientError();
    }
}
