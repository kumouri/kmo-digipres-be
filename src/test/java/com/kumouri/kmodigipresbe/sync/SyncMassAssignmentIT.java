package com.kumouri.kmodigipresbe.sync;

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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-07 — end-to-end proof that the mobile sync {@code push} path cannot
 * be used for mass-assignment / cross-tenant write-out. A push that tries to {@code $set}
 * {@code tenantId} (and other non-writable fields) must leave the document in its tenant
 * with those fields untouched, while a legit allowlisted field still applies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class SyncMassAssignmentIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), "sync_cursors").block();
        mongo.remove(new Query(), "tombstones").block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("syncma-it-" + tenantId)
                .displayName("Sync MA IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@syncma.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@syncma.test");
    }

    @Test
    void pushCannotReassignTenantId_butAppliesLegitField() {
        UUID created = createContact("Keep", "Me");
        UUID foreignTenant = UUID.randomUUID();

        // Attacker push: try to move the doc to a foreign tenant + bump version, while
        // also changing a legit field.
        List<Map> results = web.post().uri("/sync/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", created.toString(),
                        "fields", Map.of(
                                "tenantId", foreignTenant.toString(),
                                "version", 999,
                                "firstName", "Patched"),
                        "clientUpdatedAt", "1970-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("applied")).isEqualTo(true);

        // The doc stays in the original tenant; legit field applied.
        Contact after = mongo.findById(created, Contact.class).block();
        assertThat(after).isNotNull();
        assertThat(after.getTenantId()).isEqualTo(tenantId);
        assertThat(after.getTenantId()).isNotEqualTo(foreignTenant);
        assertThat(after.getFirstName()).isEqualTo("Patched");
    }

    @Test
    void pushOfOnlyNonWritableFieldsAppliesNothing() {
        UUID created = createContact("Stay", "Same");

        List<Map> results = web.post().uri("/sync/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", created.toString(),
                        "fields", Map.of("tenantId", UUID.randomUUID().toString(), "version", 7),
                        "clientUpdatedAt", "1970-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(results).hasSize(1);
        // Nothing writable left → applied=false, no Mongo write.
        assertThat(results.get(0).get("applied")).isEqualTo(false);

        Contact after = mongo.findById(created, Contact.class).block();
        assertThat(after).isNotNull();
        assertThat(after.getTenantId()).isEqualTo(tenantId);
    }

    private UUID createContact(String first, String last) {
        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("type", "PERSON", "firstName", first, "lastName", last))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString((String) created.get("id"));
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
