package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Portal profile GET/PUT IT — tests authenticated portal flow end-to-end via a minted
 * JWT and {@code Authorization: Bearer}. Covers tenant-smuggling defenses, the 1252
 * (no linked contact) error path, and cross-tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalProfileIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantAId;
    private UUID tenantBId;
    private User userA;
    private User userB;
    private Contact contactA;
    private Contact contactB;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantAId).slug("pf-a-" + tenantAId).displayName("Tenant A")
                .status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder()
                .id(tenantBId).slug("pf-b-" + tenantBId).displayName("Tenant B")
                .status(Tenant.TenantStatus.ACTIVE).build()).block();

        contactA = Contact.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantAId)
                .firstName("Alice").lastName("Adams").displayName("Alice A")
                .emails(List.of(new EmailContact("alice@a.test")))
                .phones(List.of(new PhoneNumber("555-0100", "mobile")))
                .addresses(List.of())
                .ownerId(UUID.randomUUID())
                .build();
        mongo.save(contactA).block();

        contactB = Contact.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantBId)
                .firstName("Bob").lastName("Beach").displayName("Bob B")
                .emails(List.of(new EmailContact("bob@b.test")))
                .build();
        mongo.save(contactB).block();

        userA = User.builder()
                .id(UUID.randomUUID()).tenantId(tenantAId)
                .email("alice@a.test").displayName("Alice")
                .roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId())
                .build();
        users.save(userA).block();

        userB = User.builder()
                .id(UUID.randomUUID()).tenantId(tenantBId)
                .email("bob@b.test").displayName("Bob")
                .roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId())
                .build();
        users.save(userB).block();
    }

    @Test
    void unauthenticatedGetRejected() {
        web.get().uri("/portal/me/profile").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void getProfileReturnsLinkedContact() {
        String token = jwt.mint(userA);

        web.get().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(contactA.getId().toString())
                .jsonPath("$.firstName").isEqualTo("Alice")
                .jsonPath("$.lastName").isEqualTo("Adams")
                .jsonPath("$.displayName").isEqualTo("Alice A")
                .jsonPath("$.emails[0]").isEqualTo("alice@a.test")
                .jsonPath("$.phones[0].number").isEqualTo("555-0100")
                .jsonPath("$.tenantId").doesNotExist()
                .jsonPath("$.ownerId").doesNotExist();
    }

    @Test
    void putProfileUpdatesEditableFields() {
        String token = jwt.mint(userA);

        web.put().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of(
                        "firstName", "Alicia",
                        "displayName", "Alicia A.",
                        "phones", List.of(Map.of("number", "555-9999", "label", "work"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Alicia")
                .jsonPath("$.displayName").isEqualTo("Alicia A.")
                .jsonPath("$.phones[0].number").isEqualTo("555-9999");

        Contact reloaded = mongo.findById(contactA.getId(), Contact.class).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getFirstName()).isEqualTo("Alicia");
        assertThat(reloaded.getDisplayName()).isEqualTo("Alicia A.");
        assertThat(reloaded.getTenantId()).isEqualTo(tenantAId);
        assertThat(reloaded.getOwnerId()).isEqualTo(contactA.getOwnerId());
    }

    @Test
    void putIgnoresSmuggledTenantIdAndOwnerId() {
        String token = jwt.mint(userA);
        UUID foreignTenant = UUID.randomUUID();
        UUID foreignOwner = UUID.randomUUID();

        web.put().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of(
                        "firstName", "Smuggle",
                        "tenantId", foreignTenant.toString(),
                        "ownerId", foreignOwner.toString()))
                .exchange()
                .expectStatus().isOk();

        Contact reloaded = mongo.findById(contactA.getId(), Contact.class).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getFirstName()).isEqualTo("Smuggle");
        assertThat(reloaded.getTenantId()).isEqualTo(tenantAId);
        assertThat(reloaded.getOwnerId()).isEqualTo(contactA.getOwnerId());
    }

    @Test
    void putIgnoresSmuggledContactId() {
        String token = jwt.mint(userA);
        UUID foreignContactId = contactB.getId();

        web.put().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of(
                        "id", foreignContactId.toString(),
                        "firstName", "OnlyMine"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(contactA.getId().toString());

        Contact reloadedA = mongo.findById(contactA.getId(), Contact.class).block();
        Contact reloadedB = mongo.findById(contactB.getId(), Contact.class).block();
        assertThat(reloadedA).isNotNull();
        assertThat(reloadedA.getFirstName()).isEqualTo("OnlyMine");
        assertThat(reloadedB).isNotNull();
        assertThat(reloadedB.getFirstName()).isEqualTo("Bob");
    }

    @Test
    void userWithNullContactIdReturns404() {
        User unlinked = User.builder()
                .id(UUID.randomUUID()).tenantId(tenantAId)
                .email("unlinked@a.test").displayName("Unlinked")
                .roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(null)
                .build();
        users.save(unlinked).block();
        String token = jwt.mint(unlinked);

        web.get().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1252);

        web.put().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("firstName", "ShouldNotApply"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void crossTenantUserCannotSeeOtherTenantsContact() {
        String tokenB = jwt.mint(userB);

        // user-B fetching their own profile sees only B's contact, never A's
        web.get().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(contactB.getId().toString())
                .jsonPath("$.firstName").isEqualTo("Bob");

        // Even if A's contact id is smuggled in PUT, only B's linked contact is touched.
        web.put().uri("/portal/me/profile")
                .header("Authorization", "Bearer " + tokenB)
                .bodyValue(Map.of(
                        "id", contactA.getId().toString(),
                        "firstName", "B-tried-to-edit-A"))
                .exchange()
                .expectStatus().isOk();

        Contact reloadedA = mongo.findById(contactA.getId(), Contact.class).block();
        assertThat(reloadedA).isNotNull();
        assertThat(reloadedA.getFirstName()).isEqualTo("Alice");

        long countInA = mongo.count(
                Query.query(Criteria.where("tenantId").is(tenantAId)),
                Contact.class).block();
        assertThat(countInA).isEqualTo(1);
    }
}
