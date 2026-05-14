package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class AuditControllerIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;
    private UUID createdContactId;

    @BeforeEach
    void seedAndExerciseContacts() {
        // Wipe — shared Mongo container.
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), AuditEvent.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId)
                .slug("audit-" + tenantId)
                .displayName("Audit IT")
                .status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@example.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Admin")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@example.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff")
                .roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE)
                .build()).block();

        adminToken = login("admin@example.test");
        staffToken = login("staff@example.test");

        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of(
                        "type", "PERSON",
                        "firstName", "Audited",
                        "lastName", "Person"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        createdContactId = UUID.fromString((String) created.get("id"));
    }

    @Test
    void adminCanReadAuditEventsForEntity() {
        web.get().uri(uri -> uri.path("/audit")
                        .queryParam("entityType", "Contact")
                        .queryParam("entityId", createdContactId.toString())
                        .build())
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .value(list -> {
                    assertThat(list).isNotEmpty();
                    Map<?, ?> first = list.get(0);
                    assertThat(first.get("entityType")).isEqualTo("Contact");
                    assertThat(first.get("op")).isEqualTo("CREATE");
                });
    }

    @Test
    void staffCannotReadAuditEvents_403WithErrorCode1800() {
        web.get().uri(uri -> uri.path("/audit")
                        .queryParam("entityType", "Contact")
                        .queryParam("entityId", createdContactId.toString())
                        .build())
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void unauthenticatedReturns401() {
        web.get().uri(uri -> uri.path("/audit")
                        .queryParam("entityType", "Contact")
                        .queryParam("entityId", createdContactId.toString())
                        .build())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
