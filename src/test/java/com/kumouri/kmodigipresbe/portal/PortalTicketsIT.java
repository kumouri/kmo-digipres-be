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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalTicketsIT {

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
        tenants.save(Tenant.builder().id(tenantAId).slug("tk-a-" + tenantAId)
                .displayName("A").status(Tenant.TenantStatus.ACTIVE).build()).block();
        tenants.save(Tenant.builder().id(tenantBId).slug("tk-b-" + tenantBId)
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
    }

    @Test
    void unauthenticatedRejected() {
        web.post().uri("/portal/me/tickets")
                .bodyValue(Map.of("summary", "x"))
                .exchange().expectStatus().isUnauthorized();
        web.get().uri("/portal/me/tickets").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void createTicketPersistsAsInboundContactScopedActivity() {
        String token = jwt.mint(userA);

        web.post().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("summary", "Login broken", "body", "I can't sign in."))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.summary").isEqualTo("Login broken")
                .jsonPath("$.body").isEqualTo("I can't sign in.")
                .jsonPath("$.id").exists()
                .jsonPath("$.tenantId").doesNotExist()
                .jsonPath("$.subjectId").doesNotExist()
                .jsonPath("$.direction").doesNotExist();

        List<Activity> saved = mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantAId)),
                Activity.class).collectList().block();
        assertThat(saved).hasSize(1);
        Activity a = saved.get(0);
        assertThat(a.getType()).isEqualTo(ActivityType.TICKET);
        assertThat(a.getDirection()).isEqualTo(ActivityDirection.INBOUND);
        assertThat(a.getSubjectType()).isEqualTo(SubjectType.CONTACT);
        assertThat(a.getSubjectId()).isEqualTo(contactA.getId());
        assertThat(a.getOccurredAt()).isNotNull();
    }

    @Test
    void listTicketsReturnsOnlyTickets() {
        String token = jwt.mint(userA);

        // Pre-seed a non-ticket activity and a ticket
        mongo.save(Activity.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .type(ActivityType.NOTE).direction(ActivityDirection.INTERNAL)
                .subjectType(SubjectType.CONTACT).subjectId(contactA.getId())
                .summary("Internal note").body("Staff-only").occurredAt(Instant.now())
                .build()).block();
        mongo.save(Activity.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .type(ActivityType.TICKET).direction(ActivityDirection.INBOUND)
                .subjectType(SubjectType.CONTACT).subjectId(contactA.getId())
                .summary("Help me").body("Plz").occurredAt(Instant.now())
                .build()).block();

        web.get().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].summary").isEqualTo("Help me");
    }

    @Test
    void crossTenantIsolation() {
        // Pre-seed a ticket in tenant A linked to contact A
        mongo.save(Activity.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .type(ActivityType.TICKET).direction(ActivityDirection.INBOUND)
                .subjectType(SubjectType.CONTACT).subjectId(contactA.getId())
                .summary("A ticket").occurredAt(Instant.now())
                .build()).block();

        String tokenB = jwt.mint(userB);
        web.get().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);

        // POST as B creates ticket in tenant B, not A
        web.post().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + tokenB)
                .bodyValue(Map.of("summary", "B ticket"))
                .exchange()
                .expectStatus().isCreated();

        long aCount = mongo.count(Query.query(Criteria.where("tenantId").is(tenantAId)
                .and("type").is("TICKET")), Activity.class).block();
        long bCount = mongo.count(Query.query(Criteria.where("tenantId").is(tenantBId)
                .and("type").is("TICKET")), Activity.class).block();
        assertThat(aCount).isEqualTo(1);
        assertThat(bCount).isEqualTo(1);
    }

    @Test
    void userWithNullContactIdReturns404() {
        User unlinked = User.builder().id(UUID.randomUUID()).tenantId(tenantAId)
                .email("u@a.test").roles(Set.of("CLIENT")).status(User.UserStatus.ACTIVE)
                .portal(User.Portal.CLIENT).contactId(null).build();
        users.save(unlinked).block();
        String token = jwt.mint(unlinked);

        web.post().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("summary", "no"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1252);

        web.get().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1252);
    }

    @Test
    void blankSummaryRejected() {
        String token = jwt.mint(userA);

        web.post().uri("/portal/me/tickets")
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("summary", "", "body", "details"))
                .exchange()
                .expectStatus().is4xxClientError();
    }
}
