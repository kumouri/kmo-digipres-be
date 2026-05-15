package com.kumouri.kmodigipresbe.sync;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.sync.SyncChange;
import com.kumouri.kmodigipresbe.model.sync.SyncMutation;
import com.kumouri.kmodigipresbe.model.sync.SyncPushResult;
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
class SyncServiceIT {

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
                .id(tenantId).slug("sync-it-" + tenantId)
                .displayName("Sync IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@sync.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@sync.test");
    }

    @Test
    void pullReturnsContactsCreatedAfterSinceCursor() {
        // Create a contact server-side
        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("type", "PERSON", "firstName", "Sync", "lastName", "Test"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID contactId = UUID.fromString((String) created.get("id"));

        // Pull from epoch — should include the newly created contact
        List<Map> changes = web.get()
                .uri("/sync/contacts?since=1970-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(changes).isNotNull().isNotEmpty();
        boolean found = changes.stream()
                .anyMatch(c -> "UPDATE".equals(c.get("type")) && contactId.toString().equals(c.get("id")));
        assertThat(found).isTrue();
    }

    @Test
    void pushMutationUpdatesContactField() {
        // Create a contact
        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("type", "PERSON", "firstName", "Before", "lastName", "Push"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID contactId = UUID.fromString((String) created.get("id"));

        // Push a mutation changing firstName
        List<Map> results = web.post()
                .uri("/sync/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", contactId.toString(),
                        "fields", Map.of("firstName", "After"),
                        "clientUpdatedAt", "1970-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("applied")).isEqualTo(true);

        // Verify the update
        Map<?, ?> fetched = web.get().uri("/contacts/" + contactId)
                .header("Authorization", "Bearer " + staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(fetched.get("firstName")).isEqualTo("After");
    }

    @Test
    void pushToNonExistentIdReturnsFalse() {
        UUID nonExistent = UUID.randomUUID();
        List<Map> results = web.post()
                .uri("/sync/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(List.of(Map.of(
                        "id", nonExistent.toString(),
                        "fields", Map.of("firstName", "Ghost"),
                        "clientUpdatedAt", "1970-01-01T00:00:00Z")))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("applied")).isEqualTo(false);
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
