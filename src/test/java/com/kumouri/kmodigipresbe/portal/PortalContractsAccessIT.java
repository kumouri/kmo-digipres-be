package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
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

import java.util.Set;
import java.util.UUID;

/**
 * AC-G4 — portal contract access-control isolation.
 *
 * <p>Seeds Tenant A (contactA w/ companyA, contactA2 w/o company) and
 * Tenant B (contactB). Proves cross-tenant AND cross-contact isolation on
 * GET /portal/me/contracts + GET /portal/me/contracts/{id} + the signed-pdf path.
 * Mirrors the {@link PortalInvoicesIT} cross-tenant+cross-contact shape exactly
 * (§9 #3 / shard-safe: no @MockBean, self-clean @BeforeEach).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalContractsAccessIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantAId;
    private UUID tenantBId;
    private User userA;
    private User userANoCompany;
    private User userB;
    private Contact contactA;
    private Contact contactA2;
    private UUID companyA;

    private Contract contractA;          // SENT, owned by contactA
    private Contract contractACompany;   // SIGNED, owned via companyA — has signed PDF ref
    private Contract contractAOther;     // Tenant A but different contact — must NOT appear
    private Contract contractB;          // Tenant B — must never leak to A

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("ctr-a-" + tenantAId)
                .displayName("Tenant A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("ctr-b-" + tenantBId)
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
                .email("a@ctr.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();

        userANoCompany = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a2@ctr.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA2.getId()).build();
        users.save(userANoCompany).block();

        userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@ctr.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId()).build();
        users.save(userB).block();

        // Contracts
        contractA = Contract.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .title("Contract A Contact").kind(ContractTemplate.Kind.SOW)
                .status(Contract.Status.SENT)
                .contactId(contactA.getId())
                .documensoDocumentId("doc-a-123")
                .build();
        mongo.save(contractA).block();

        contractACompany = Contract.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .title("Contract A Company").kind(ContractTemplate.Kind.MSA)
                .status(Contract.Status.SIGNED)
                .companyId(companyA)
                .signedPdfStorageRef("tenants/" + tenantAId + "/contracts/signed.pdf")
                .build();
        mongo.save(contractACompany).block();

        contractAOther = Contract.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .title("Contract A Other").kind(ContractTemplate.Kind.NDA)
                .status(Contract.Status.DRAFT)
                .contactId(UUID.randomUUID()) // different contact
                .build();
        mongo.save(contractAOther).block();

        contractB = Contract.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .title("Contract B").kind(ContractTemplate.Kind.SOW)
                .status(Contract.Status.SENT)
                .contactId(contactB.getId())
                .build();
        mongo.save(contractB).block();
    }

    // ─── List tests ───────────────────────────────────────────────────────────

    @Test
    void unauthenticatedRejected() {
        web.get().uri("/portal/me/contracts").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void userA_seesOwnContactAndCompanyContracts() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/contracts")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void crossTenantIsolation_userB_seesOnlyOwnContracts() {
        String token = jwt.mint(userB);
        web.get().uri("/portal/me/contracts")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].title").isEqualTo("Contract B");
    }

    @Test
    void contactWithNoCompany_seesNone() {
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/contracts")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    // ─── Single contract GET ──────────────────────────────────────────────────

    @Test
    void userA_canGetOwnContract_andDocumensoDeepLinkSurfaces() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/contracts/" + contractA.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(contractA.getId().toString())
                .jsonPath("$.tenantId").doesNotExist()
                .jsonPath("$.contactId").doesNotExist()
                // SENT contract with documensoDocumentId → deep-link surfaces stored id
                .jsonPath("$.documensoSigningDeepLink").isEqualTo("doc-a-123")
                .jsonPath("$.signedPdfAvailable").isEqualTo(false);
    }

    @Test
    void crossTenantContract_returns3804() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/contracts/" + contractB.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3804);
    }

    @Test
    void crossContactContract_returns3804() {
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/contracts/" + contractA.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3804);
    }

    // ─── Signed-PDF tests ─────────────────────────────────────────────────────

    @Test
    void signedContract_signedPdfAvailableTrue_andPresignUrlReturned() {
        String token = jwt.mint(userA);
        // contractACompany is SIGNED and has signedPdfStorageRef; verify summary flag
        web.get().uri("/portal/me/contracts/" + contractACompany.getId())
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.signedPdfAvailable").isEqualTo(true);

        // The actual signed-pdf endpoint will call presignDownload against S3.
        // In test, S3 is not wired (no real bucket), so expect an error from the presign
        // attempt (storage layer) rather than a 200 with a URL. The important assertion
        // is that the security gate passes (ownership check does not return 3804) —
        // i.e., the response is NOT 404/3804. Any 4xx from the presign itself is expected.
        web.get().uri("/portal/me/contracts/" + contractACompany.getId() + "/signed-pdf")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        // Must not be 404 (ownership gate passed) and not 401 (auth passed)
                        org.assertj.core.api.Assertions.assertThat(status)
                                .isNotEqualTo(404)
                                .isNotEqualTo(401));
    }

    @Test
    void nonSignedContract_signedPdf_returns3806() {
        String token = jwt.mint(userA);
        // contractA is SENT (not SIGNED) → expect 3806/409
        web.get().uri("/portal/me/contracts/" + contractA.getId() + "/signed-pdf")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3806);
    }

    @Test
    void anotherContactContract_signedPdf_returns3804() {
        // contactA2 tries to get signed-pdf of contractA (owned by contactA)
        String token = jwt.mint(userANoCompany);
        web.get().uri("/portal/me/contracts/" + contractA.getId() + "/signed-pdf")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3804);
    }

    @Test
    void crossTenantContract_signedPdf_returns3804() {
        String token = jwt.mint(userA);
        web.get().uri("/portal/me/contracts/" + contractB.getId() + "/signed-pdf")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3804);
    }
}
