package com.kumouri.kmodigipresbe.timetracking;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D1: Manual entry (POST /time-entries) is first-class (Decision D9).
 * Same-local-day entry → exactly one row, splitGroupId==null, durationSeconds correct,
 * billingStatus=UNBILLED.
 * Validation: endedAt < startedAt → 400/3503; null startedAt → 400/3502.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class TimeEntryLifecycleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("te-it-" + tenantId)
                .displayName("TE IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@te.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void manualEntrySameDayProducesOneRowNullSplitGroup() {
        // AC-D1: same-local-day session → 1 row, splitGroupId==null, source=MANUAL, UNBILLED
        Instant start = Instant.parse("2026-05-16T09:00:00Z");
        Instant end   = Instant.parse("2026-05-16T11:00:00Z"); // same UTC day → 7200s

        String body = """
                {"startedAt":"%s","endedAt":"%s","description":"Design work"}
                """.formatted(start.toString(), end.toString());

        web.post().uri("/time-entries")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.source").isEqualTo("MANUAL")
                .jsonPath("$.billingStatus").isEqualTo("UNBILLED")
                .jsonPath("$.durationSeconds").isEqualTo(7200)
                .jsonPath("$.splitGroupId").doesNotExist();

        List<TimeEntry> rows = mongo.findAll(TimeEntry.class).collectList().block();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSplitGroupId()).isNull();
        assertThat(rows.get(0).getDurationSeconds()).isEqualTo(7200L);
    }

    @Test
    void endedAtBeforeStartedAtReturns400With3503() {
        // AC-D1: endedAt < startedAt → 400 3503
        Instant start = Instant.parse("2026-05-16T12:00:00Z");
        Instant end   = Instant.parse("2026-05-16T10:00:00Z");

        String body = """
                {"startedAt":"%s","endedAt":"%s"}
                """.formatted(start, end);

        web.post().uri("/time-entries")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3503);
    }

    @Test
    void nullStartedAtReturns400With3502() {
        // AC-D1: null startedAt → 400 3502
        String body = """
                {"endedAt":"2026-05-16T11:00:00Z","description":"No start"}
                """;

        web.post().uri("/time-entries")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3502);
    }
}
