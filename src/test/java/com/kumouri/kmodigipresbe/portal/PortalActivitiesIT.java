package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalActivitiesIT {

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
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantAId).slug("act-a-" + tenantAId)
                .displayName("A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("act-b-" + tenantBId)
                .displayName("B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        contactA = Contact.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .firstName("Alice").lastName("A").build();
        mongo.save(contactA).block();
        contactB = Contact.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .firstName("Bob").lastName("B").build();
        mongo.save(contactB).block();

        userA = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("a@a.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactA.getId()).build();
        users.save(userA).block();
        userB = User.builder().id(UUID.randomUUID()).tenantId(tenantBId)
                .email("b@b.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(contactB.getId()).build();
        users.save(userB).block();

        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // Two activities tied to A's Contact (different occurredAt for sort assertion)
        mongo.save(activity(tenantAId, ActivityType.NOTE, SubjectType.CONTACT,
                contactA.getId(), "Older note", "Body 1", t0.minus(2, ChronoUnit.DAYS))).block();
        mongo.save(activity(tenantAId, ActivityType.EMAIL, SubjectType.CONTACT,
                contactA.getId(), "Recent email", "Body 2", t0.minus(1, ChronoUnit.HOURS))).block();
        // Activity in A but tied to a different subject (should NOT match)
        mongo.save(activity(tenantAId, ActivityType.NOTE, SubjectType.COMPANY,
                UUID.randomUUID(), "Company note", "Body 3", t0)).block();
        // Activity in B tied to B's contact (must NOT leak to A)
        mongo.save(activity(tenantBId, ActivityType.CALL, SubjectType.CONTACT,
                contactB.getId(), "B call", "Body 4", t0)).block();
    }

    private static Activity activity(UUID tenantId, ActivityType type, SubjectType subjectType,
                                     UUID subjectId, String summary, String body,
                                     Instant occurredAt) {
        return Activity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .type(type)
                .direction(ActivityDirection.OUTBOUND)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .summary(summary)
                .body(body)
                .occurredAt(occurredAt)
                .build();
    }

    @Test
    void unauthenticatedRejected() {
        web.get().uri("/portal/me/activities").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void listsContactSubjectsOnlySortedDesc() {
        String token = jwt.mint(userA);

        web.get().uri("/portal/me/activities")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].summary").isEqualTo("Recent email")
                .jsonPath("$[1].summary").isEqualTo("Older note")
                .jsonPath("$[0].type").isEqualTo("EMAIL")
                .jsonPath("$[0].body").isEqualTo("Body 2")
                .jsonPath("$[0].tenantId").doesNotExist()
                .jsonPath("$[0].subjectId").doesNotExist()
                .jsonPath("$[0].ownerId").doesNotExist();
    }

    @Test
    void crossTenantIsolation() {
        String tokenB = jwt.mint(userB);

        web.get().uri("/portal/me/activities")
                .header("Authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].summary").isEqualTo("B call");
    }

    @Test
    void userWithNullContactIdReturns404() {
        User unlinked = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("u@a.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(null).build();
        users.save(unlinked).block();
        String token = jwt.mint(unlinked);

        web.get().uri("/portal/me/activities")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1252);
    }
}
