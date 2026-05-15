package com.kumouri.kmodigipresbe.sync;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.sync.Tombstone;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.TombstoneRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class SyncTombstoneIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired TombstoneRepository tombstoneRepo;

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
                .id(tenantId).slug("tombstone-it-" + tenantId)
                .displayName("Tombstone IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@tombstone.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@tombstone.test");
    }

    @Test
    void pulledChangesIncludeDeleteForTombstonedContact() {
        // Create a contact
        Map<?, ?> created = web.post().uri("/contacts")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("type", "PERSON", "firstName", "To", "lastName", "Delete"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        UUID contactId = UUID.fromString((String) created.get("id"));

        // Directly seed a tombstone for this contact (simulating ContactCrudService.delete)
        UUID deletedId = contactId;
        tombstoneRepo.save(Tombstone.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .collection("contacts")
                .deletedId(deletedId)
                .deletedAt(Instant.now())
                .build()).block();

        // Pull from epoch
        List<Map> changes = web.get()
                .uri("/sync/contacts?since=1970-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();

        assertThat(changes).isNotNull();
        boolean deleteSeen = changes.stream()
                .anyMatch(c -> "DELETE".equals(c.get("type"))
                        && deletedId.toString().equals(c.get("id")));
        assertThat(deleteSeen).as("DELETE tombstone must appear in pull result").isTrue();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
