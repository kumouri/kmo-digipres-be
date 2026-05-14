package com.kumouri.kmodigipresbe.contact;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@code POST /public/{tenantSlug}/contacts}. Mirrors
 * {@code AuthSmokeIT} style: real Spring context + Mongo testcontainer,
 * {@link WebTestClient} against a random port, one method per behavioral
 * assertion. {@link com.kumouri.kmodigipresbe.tenancy.TenantStampingCallback}
 * + {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository}
 * are exercised end-to-end here — we verify writes by direct mongo read so the
 * test doesn't need to spin up a staff JWT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PublicContactIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID activeTenantId;
    private String activeSlug;
    private String inactiveSlug;

    @BeforeEach
    void seed() {
        // Wipe collections we touch — Mongo container is reused across tests.
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        activeTenantId = UUID.randomUUID();
        activeSlug = "pub-active-" + activeTenantId;
        tenants.save(Tenant.builder()
                .id(activeTenantId)
                .slug(activeSlug)
                .displayName("Public Active")
                .status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        UUID inactiveId = UUID.randomUUID();
        inactiveSlug = "pub-inactive-" + inactiveId;
        tenants.save(Tenant.builder()
                .id(inactiveId)
                .slug(inactiveSlug)
                .displayName("Public Inactive")
                .status(Tenant.TenantStatus.SUSPENDED)
                .build()).block();
    }

    @Test
    void happyPathCreatesContactInPathTenant() {
        Map<String, Object> body = Map.of(
                "email", "happy@example.test",
                "firstName", "Happy",
                "lastName", "Path");

        web.post().uri("/public/" + activeSlug + "/contacts")
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Happy")
                .jsonPath("$.lastName").isEqualTo("Path")
                .jsonPath("$.displayName").isEqualTo("Happy Path")
                .jsonPath("$.tenantId").isEqualTo(activeTenantId.toString())
                .jsonPath("$.ownerId").isEmpty();

        List<Contact> saved = mongo.find(
                Query.query(Criteria.where("tenantId").is(activeTenantId)),
                Contact.class).collectList().block();
        assertThat(saved).hasSize(1);
        Contact c = saved.get(0);
        assertThat(c.getTenantId()).isEqualTo(activeTenantId);
        assertThat(c.getOwnerId()).isNull();
        assertThat(c.getEmails()).hasSize(1);
        assertThat(c.getEmails().get(0).asString()).isEqualTo("happy@example.test");
        assertThat(c.getTags()).contains("public-contact");
    }

    @Test
    void unknownTenantSlugReturns404() {
        web.post().uri("/public/no-such-tenant/contacts")
                .bodyValue(Map.of("email", "x@example.test", "firstName", "X"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void inactiveTenantReturns404() {
        web.post().uri("/public/" + inactiveSlug + "/contacts")
                .bodyValue(Map.of("email", "x@example.test", "firstName", "X"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void pathDerivedTenantIsStampedRegardlessOfRequestExtras() {
        // PublicContactRequest is a strict record with no tenantId field, so
        // Jackson will simply ignore an unknown 'tenantId' in the body. That's
        // the intended defense: the request shape physically cannot carry a
        // foreign tenant. This test pins the contract — even when a client
        // tries to smuggle one in, the saved doc's tenantId matches the path.
        UUID foreignTenantId = UUID.randomUUID();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("email", "foreign@example.test");
        body.put("firstName", "Foreign");
        body.put("tenantId", foreignTenantId.toString());
        body.put("ownerId", UUID.randomUUID().toString());

        web.post().uri("/public/" + activeSlug + "/contacts")
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();

        List<Contact> saved = mongo.find(
                Query.query(Criteria.where("tenantId").is(activeTenantId)),
                Contact.class).collectList().block();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTenantId()).isEqualTo(activeTenantId);
        assertThat(saved.get(0).getOwnerId()).isNull();
    }

    @Test
    void rateLimitExceededReturns429() {
        // The filter caps at 10 requests / minute / IP+tenant. Fire 10 valid
        // requests, then the 11th must be a 429. Use a fresh tenant so we don't
        // collide with other tests sharing the same client IP+slug bucket.
        UUID rlTenantId = UUID.randomUUID();
        String rlSlug = "pub-rl-" + rlTenantId;
        tenants.save(Tenant.builder()
                .id(rlTenantId)
                .slug(rlSlug)
                .displayName("Public RL")
                .status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        Map<String, Object> body = Map.of("email", "rl@example.test", "firstName", "RL");
        for (int i = 0; i < 10; i++) {
            web.post().uri("/public/" + rlSlug + "/contacts")
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isCreated();
        }
        web.post().uri("/public/" + rlSlug + "/contacts")
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void missingEmailReturns400() {
        web.post().uri("/public/" + activeSlug + "/contacts")
                .bodyValue(Map.of("firstName", "NoEmail"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void bothNamesAbsentReturns400() {
        web.post().uri("/public/" + activeSlug + "/contacts")
                .bodyValue(Map.of("email", "anon@example.test"))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
