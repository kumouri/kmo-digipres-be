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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for {@code POST /public/{tenantSlug}/newsletter/subscribe}. Mirrors
 * {@link PublicContactIT}. Verifies tenant resolution, idempotent merge on existing
 * email, smuggled-tenantId defense, rate-limit (including cross-endpoint bucket
 * sharing with {@code /contacts}), and that the response shape does not leak
 * staff-internal Contact fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PublicNewsletterIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID activeTenantId;
    private String activeSlug;
    private String inactiveSlug;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        activeTenantId = UUID.randomUUID();
        activeSlug = "nl-active-" + activeTenantId;
        tenants.save(Tenant.builder().id(activeTenantId).slug(activeSlug)
                .displayName("NL Active").status(Tenant.TenantStatus.ACTIVE).build()).block();

        UUID inactiveId = UUID.randomUUID();
        inactiveSlug = "nl-inactive-" + inactiveId;
        tenants.save(Tenant.builder().id(inactiveId).slug(inactiveSlug)
                .displayName("NL Inactive").status(Tenant.TenantStatus.SUSPENDED).build()).block();
    }

    @Test
    void newEmailCreatesContactWithNewsletterTag() {
        web.post().uri("/public/" + activeSlug + "/newsletter/subscribe")
                .bodyValue(Map.of(
                        "email", "new@example.test",
                        "firstName", "New",
                        "topics", List.of("product", "deals")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.email").isEqualTo("new@example.test")
                .jsonPath("$.status").isEqualTo("subscribed")
                .jsonPath("$.topics.length()").isEqualTo(2)
                .jsonPath("$.ownerId").doesNotExist()
                .jsonPath("$.customFields").doesNotExist()
                .jsonPath("$.tenantId").doesNotExist();

        List<Contact> saved = mongo.find(
                Query.query(Criteria.where("tenantId").is(activeTenantId)),
                Contact.class).collectList().block();
        assertThat(saved).hasSize(1);
        Contact c = saved.get(0);
        assertThat(c.getTenantId()).isEqualTo(activeTenantId);
        assertThat(c.getTags()).contains("newsletter");
        assertThat(c.getSubscriptionTopics()).containsExactlyInAnyOrder("product", "deals");
        assertThat(c.getEmails()).hasSize(1);
    }

    @Test
    void existingEmailMergesTopicsIdempotently() {
        // First call: create
        web.post().uri("/public/" + activeSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "repeat@example.test", "topics", List.of("product")))
                .exchange()
                .expectStatus().isCreated();

        // Second call: same email, new topic → merge, no duplicate
        web.post().uri("/public/" + activeSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "repeat@example.test", "topics", List.of("deals")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("already_subscribed")
                .jsonPath("$.topics.length()").isEqualTo(2);

        List<Contact> saved = mongo.find(
                Query.query(Criteria.where("tenantId").is(activeTenantId)),
                Contact.class).collectList().block();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getSubscriptionTopics())
                .containsExactlyInAnyOrder("product", "deals");
        assertThat(saved.get(0).getTags()).contains("newsletter");
    }

    @Test
    void unknownTenantReturns404() {
        web.post().uri("/public/no-such-tenant/newsletter/subscribe")
                .bodyValue(Map.of("email", "x@example.test"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void inactiveTenantReturns404() {
        web.post().uri("/public/" + inactiveSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "x@example.test"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void smuggledTenantIdIsIgnored() {
        UUID foreign = UUID.randomUUID();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("email", "smug@example.test");
        body.put("tenantId", foreign.toString());
        body.put("ownerId", UUID.randomUUID().toString());

        web.post().uri("/public/" + activeSlug + "/newsletter/subscribe")
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
    void missingEmailReturns400() {
        web.post().uri("/public/" + activeSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("firstName", "NoEmail"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rateLimitExceededReturns429() {
        // Fresh slug to isolate the (IP, slug) bucket from other tests on the same client IP.
        UUID rlTenantId = UUID.randomUUID();
        String rlSlug = "nl-rl-" + rlTenantId;
        tenants.save(Tenant.builder().id(rlTenantId).slug(rlSlug)
                .displayName("NL RL").status(Tenant.TenantStatus.ACTIVE).build()).block();

        for (int i = 0; i < 10; i++) {
            web.post().uri("/public/" + rlSlug + "/newsletter/subscribe")
                    .bodyValue(Map.of("email", "rl" + i + "@example.test"))
                    .exchange()
                    .expectStatus().is2xxSuccessful();
        }
        web.post().uri("/public/" + rlSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "rl-over@example.test"))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void bucketIsSharedAcrossPublicContactsAndNewsletter() {
        // Same (IP, slug) bucket spans both lead-capture endpoints. 5 contacts +
        // 5 newsletter = 10 hits; the 11th (whichever endpoint) → 429.
        UUID mixTenantId = UUID.randomUUID();
        String mixSlug = "nl-mix-" + mixTenantId;
        tenants.save(Tenant.builder().id(mixTenantId).slug(mixSlug)
                .displayName("Mix").status(Tenant.TenantStatus.ACTIVE).build()).block();

        for (int i = 0; i < 5; i++) {
            web.post().uri("/public/" + mixSlug + "/contacts")
                    .bodyValue(Map.of(
                            "email", "c" + i + "@example.test",
                            "firstName", "C" + i))
                    .exchange()
                    .expectStatus().isCreated();
        }
        for (int i = 0; i < 5; i++) {
            web.post().uri("/public/" + mixSlug + "/newsletter/subscribe")
                    .bodyValue(Map.of("email", "n" + i + "@example.test"))
                    .exchange()
                    .expectStatus().is2xxSuccessful();
        }
        web.post().uri("/public/" + mixSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "over@example.test"))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void crossTenantIsolation() {
        // Same email used in two tenants must yield two distinct Contacts.
        UUID otherTenantId = UUID.randomUUID();
        String otherSlug = "nl-other-" + otherTenantId;
        tenants.save(Tenant.builder().id(otherTenantId).slug(otherSlug)
                .displayName("Other").status(Tenant.TenantStatus.ACTIVE).build()).block();

        web.post().uri("/public/" + activeSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "shared@example.test", "topics", List.of("a")))
                .exchange()
                .expectStatus().isCreated();
        web.post().uri("/public/" + otherSlug + "/newsletter/subscribe")
                .bodyValue(Map.of("email", "shared@example.test", "topics", List.of("b")))
                .exchange()
                .expectStatus().isCreated();

        long aCount = mongo.count(Query.query(Criteria.where("tenantId").is(activeTenantId)),
                Contact.class).block();
        long otherCount = mongo.count(Query.query(Criteria.where("tenantId").is(otherTenantId)),
                Contact.class).block();
        assertThat(aCount).isEqualTo(1);
        assertThat(otherCount).isEqualTo(1);

        Contact aContact = mongo.find(Query.query(Criteria.where("tenantId").is(activeTenantId)),
                Contact.class).blockFirst();
        assertThat(aContact).isNotNull();
        assertThat(aContact.getSubscriptionTopics()).isEqualTo(Set.of("a"));
    }
}
