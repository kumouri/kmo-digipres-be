package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
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
 * AC-G4 + activity — portal invoice-view telemetry (§9 #1).
 *
 * <p>Proves:
 * <ul>
 *   <li>GET /portal/me/invoices/{id} for an owned invoice → 200 PortalInvoiceSummary</li>
 *   <li>An Activity row is created (type=NOTE, subjectType=CONTACT, subjectId=contactA.id,
 *       payload.invoiceId=ownedInvoice.id) after the view</li>
 *   <li>INVOICE_VIEWED_BY_CLIENT domain event is observed</li>
 *   <li>Viewing another contact's invoice → 3801 same-404 AND no Activity row created
 *       (telemetry isolation proof)</li>
 * </ul>
 * Shard-safe: no @MockBean, self-clean @BeforeEach, independent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalInvoiceViewActivityIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired DomainEventPublisher events;

    private UUID tenantAId;
    private User userA;
    private User userANoCompany;
    private Contact contactA;
    private Contact contactA2;
    private Invoice ownedInvoice;   // owned by contactA
    private Invoice otherInvoice;   // owned by contactA2 — isolation target

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("view-a-" + tenantAId)
                .displayName("Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();

        UUID companyA = UUID.randomUUID();

        contactA = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("Alice").lastName("A").companyId(companyA).build();
        mongo.save(contactA).block();

        contactA2 = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("NoCo2").lastName("A2").build();
        mongo.save(contactA2).block();

        userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@view.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();

        userANoCompany = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a2@view.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA2.getId()).build();
        users.save(userANoCompany).block();

        ownedInvoice = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantAId)
                .invoiceNumber("INV-VIEW-001")
                .status(Invoice.Status.SENT)
                .contactId(contactA.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(new BigDecimal("200.00"))
                .build()).block();

        otherInvoice = invoiceRepository.save(Invoice.builder()
                .tenantId(tenantAId)
                .invoiceNumber("INV-VIEW-002")
                .status(Invoice.Status.SENT)
                .contactId(contactA2.getId())
                .currency("USD")
                .lineItems(List.of())
                .total(new BigDecimal("150.00"))
                .build()).block();
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    void viewOwnedInvoice_returns200_andActivityCreated_andEventObserved() {
        List<DomainEvent> captured = new CopyOnWriteArrayList<>();
        Disposable sub = events.stream()
                .filter(e -> DomainEventType.INVOICE_VIEWED_BY_CLIENT.equals(e.type()))
                .subscribe(captured::add);

        try {
            String token = jwt.mint(userA);
            web.get().uri("/portal/me/invoices/" + ownedInvoice.getId())
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.id").isEqualTo(ownedInvoice.getId().toString())
                    .jsonPath("$.tenantId").doesNotExist()
                    .jsonPath("$.contactId").doesNotExist();

            // Wait for advisory event emission (best-effort; fire-and-forget)
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !captured.isEmpty());
            assertThat(captured.get(0).type()).isEqualTo(DomainEventType.INVOICE_VIEWED_BY_CLIENT);

            // Activity row exists: type=NOTE, subjectType=CONTACT, subjectId=contactA.id,
            // and payload contains the invoiceId — bypass tenant scope via mongo template
            List<Activity> activities = mongo.findAll(Activity.class).collectList().block();
            assertThat(activities).isNotNull();
            Activity viewActivity = activities.stream()
                    .filter(a -> SubjectType.CONTACT.equals(a.getSubjectType())
                            && contactA.getId().equals(a.getSubjectId()))
                    .findFirst()
                    .orElse(null);
            assertThat(viewActivity).as("Activity row for invoice view should exist").isNotNull();
            assertThat(viewActivity.getPayload()).containsKey("invoiceId");
            String invoiceIdInPayload = viewActivity.getPayload().get("invoiceId") == null
                    ? null : viewActivity.getPayload().get("invoiceId").toString();
            assertThat(invoiceIdInPayload).isEqualTo(ownedInvoice.getId().toString());
        } finally {
            sub.dispose();
        }
    }

    // ─── Telemetry isolation: another contact's invoice must not produce an Activity ─

    @Test
    void viewAnotherContactInvoice_returns3801_andNoActivityCreated() {
        // userA tries to view otherInvoice (owned by contactA2, same tenant)
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/invoices/" + otherInvoice.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3801);

        // No Activity row should have been created (telemetry isolation)
        long activityCount = mongo.findAll(Activity.class).count().block();
        assertThat(activityCount).isZero();
    }

    // ─── Cross-contact: contactA2 views ownedInvoice → 3801 ──────────────────

    @Test
    void crossContactView_returns3801() {
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/invoices/" + ownedInvoice.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3801);
    }
}
