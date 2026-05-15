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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class SyncConflictIT {

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
                .id(tenantId).slug("conflict-it-" + tenantId)
                .displayName("Conflict IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@conflict.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@conflict.test");
    }

    @Test
    void conflictFlaggedWhenServerNewerThanClientCursor() {
        // Create a contact server-side
        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("type", "PERSON", "firstName", "Original", "lastName", "Conflict"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID contactId = UUID.fromString((String) created.get("id"));

        // Server updates the contact
        web.put().uri("/contacts/" + contactId)
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("firstName", "ServerUpdated"))
                .exchange().expectStatus().isOk();

        // Client pushes with a clientUpdatedAt in the far past (simulating stale client)
        // and a different firstName value — should detect conflict
        List<Map> results = web.post()
                .uri("/sync/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", contactId.toString(),
                        "fields", Map.of("firstName", "ClientVersion"),
                        "clientUpdatedAt", "1970-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("applied")).isEqualTo(true);
        // conflictedFields should list 'firstName' since server had a newer value
        @SuppressWarnings("unchecked")
        List<String> conflicted = (List<String>) results.get(0).get("conflictedFields");
        assertThat(conflicted).contains("firstName");

        // Client value applied (LWW)
        Map<?, ?> fetched = web.get().uri("/contacts/" + contactId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(fetched.get("firstName")).isEqualTo("ClientVersion");
    }

    @Test
    void noConflictWhenClientCursorNewer() {
        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("type", "PERSON", "firstName", "Fresh", "lastName", "NoConflict"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID contactId = UUID.fromString((String) created.get("id"));

        // Push with a clientUpdatedAt in the far future — no conflict expected
        List<Map> results = web.post()
                .uri("/sync/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", contactId.toString(),
                        "fields", Map.of("firstName", "ClientFresh"),
                        "clientUpdatedAt", "2099-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("applied")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> conflicted = (List<String>) results.get(0).get("conflictedFields");
        assertThat(conflicted).isEmpty();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
