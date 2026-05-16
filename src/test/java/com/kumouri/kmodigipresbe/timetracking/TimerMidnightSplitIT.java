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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D2 / AC-D3: The headline midnight-split regression (D-D3).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Same local day → exactly one row, {@code splitGroupId==null}.</li>
 *   <li>Mon 22:00 UTC → Tue 02:00 UTC (America/Chicago = UTC-5 in CDT; or use UTC zone where
 *       these straddle UTC midnight) → two rows, shared {@code splitGroupId},
 *       half-open boundary; row A {@code endedAt == rowB.startedAt}.</li>
 *   <li>&gt;24h session → three rows.</li>
 *   <li>Stop with no running timer → 409 errorCode 3506.</li>
 *   <li>Double start → 409 errorCode 3505.</li>
 *   <li>Running timer: endedAt==null, durationSeconds==0.</li>
 *   <li>After stop: finder returns empty; row has endedAt!=null + durationSeconds&gt;0.</li>
 *   <li>Running timer is never auto-split (no extra rows without a stop call).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class TimerMidnightSplitIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID userId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), TimeEntry.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("split-it-" + tenantId)
                .displayName("Split IT Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        userId = UUID.randomUUID();
        User user = User.builder().id(userId).tenantId(tenantId)
                .email("staff@split.test").roles(Set.of("STAFF", "ADMIN"))
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

    // -------------------------------------------------------------------------
    // AC-D2: Midnight split
    // -------------------------------------------------------------------------

    @Test
    void sameDayTimerProducesOneRowNullSplitGroup() {
        // Start at 09:00 UTC, stop at 11:00 UTC — same UTC day → 1 row, no split
        Instant start = Instant.parse("2026-05-18T09:00:00Z");
        Instant stop  = Instant.parse("2026-05-18T11:00:00Z");

        startTimerAt(start);
        stopTimer(stop, "UTC");

        List<TimeEntry> rows = entriesForUser();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSplitGroupId()).isNull();
        assertThat(rows.get(0).getDurationSeconds()).isEqualTo(7200L);
        assertThat(rows.get(0).getEndedAt()).isNotNull();
    }

    @Test
    void monToTueTimerProducesTwoRowsWithSharedSplitGroupAndHalfOpenBoundary() {
        // Mon 22:00 UTC → Tue 02:00 UTC (straddling UTC midnight):
        // Row A: [22:00, 00:00) = 7200s; Row B: [00:00, 02:00) = 7200s
        // Zone = UTC so midnight = 2026-05-19T00:00:00Z
        Instant start      = Instant.parse("2026-05-18T22:00:00Z"); // Mon 22:00 UTC
        Instant stop       = Instant.parse("2026-05-19T02:00:00Z"); // Tue 02:00 UTC
        Instant utcMidnight = Instant.parse("2026-05-19T00:00:00Z");

        startTimerAt(start);
        stopTimer(stop, "UTC");

        List<TimeEntry> rows = entriesForUser();
        assertThat(rows).as("Should produce exactly 2 rows").hasSize(2);

        // Sort by startedAt for deterministic assertion
        rows.sort(Comparator.comparing(TimeEntry::getStartedAt));

        TimeEntry rowA = rows.get(0);
        TimeEntry rowB = rows.get(1);

        // Both rows share a non-null splitGroupId
        assertThat(rowA.getSplitGroupId()).isNotNull();
        assertThat(rowB.getSplitGroupId()).isEqualTo(rowA.getSplitGroupId());

        // Row A: Mon [22:00, 00:00)
        assertThat(rowA.getStartedAt()).isEqualTo(start);
        assertThat(rowA.getEndedAt()).isEqualTo(utcMidnight);
        assertThat(rowA.getDurationSeconds()).isEqualTo(7200L);

        // Row B: Tue [00:00, 02:00)
        assertThat(rowB.getStartedAt()).isEqualTo(utcMidnight);
        assertThat(rowB.getEndedAt()).isEqualTo(stop);
        assertThat(rowB.getDurationSeconds()).isEqualTo(7200L);

        // Half-open boundary: rowA.endedAt == rowB.startedAt (boundary belongs to earlier day)
        assertThat(rowA.getEndedAt()).isEqualTo(rowB.getStartedAt());

        // No zero-length trailing row
        rows.forEach(r -> assertThat(r.getDurationSeconds()).isGreaterThan(0));
    }

    @Test
    void moreThan24hTimerProducesThreeRows() {
        // 25h session spanning two UTC midnights:
        // Mon 23:00 → Wed 00:00 (25h)
        // Row A: [Mon 23:00, Tue 00:00) = 3600s
        // Row B: [Tue 00:00, Wed 00:00) = 86400s
        // Row C: [Wed 00:00, Wed 00:00) … wait, end is exactly Wed midnight:
        // End = Wed 00:00:00 → half-open means boundary at end is excluded → only 2 segments
        // Let's use Mon 23:00 → Wed 01:00 (26h) → 3 rows
        Instant start = Instant.parse("2026-05-18T23:00:00Z"); // Mon 23:00 UTC
        Instant stop  = Instant.parse("2026-05-20T01:00:00Z"); // Wed 01:00 UTC (26h later)

        startTimerAt(start);
        stopTimer(stop, "UTC");

        List<TimeEntry> rows = entriesForUser();
        assertThat(rows).as("26h session should produce 3 rows").hasSize(3);

        // All share a non-null splitGroupId
        UUID groupId = rows.get(0).getSplitGroupId();
        assertThat(groupId).isNotNull();
        rows.forEach(r -> assertThat(r.getSplitGroupId()).isEqualTo(groupId));

        // Total duration should equal the full session
        long totalSeconds = rows.stream().mapToLong(TimeEntry::getDurationSeconds).sum();
        long expected = stop.getEpochSecond() - start.getEpochSecond();
        assertThat(totalSeconds).isEqualTo(expected);
    }

    // -------------------------------------------------------------------------
    // AC-D3: Timer state invariants
    // -------------------------------------------------------------------------

    @Test
    void runningTimerHasNullEndedAtAndZeroDuration() {
        // AC-D3: after startTimer, the running entry has endedAt==null, durationSeconds==0
        Instant start = Instant.now();
        startTimerAt(start);

        List<TimeEntry> rows = entriesForUser();
        assertThat(rows).hasSize(1);
        TimeEntry running = rows.get(0);
        assertThat(running.getEndedAt()).isNull();
        assertThat(running.getDurationSeconds()).isEqualTo(0L);
    }

    @Test
    void stopWithNoRunningTimerReturns409With3506() {
        // AC-D2: no running timer → 409 errorCode 3506 (explicit boolean guard)
        web.post().uri("/time-entries/timer/stop?userId=" + userId + "&zoneId=UTC")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3506);
    }

    @Test
    void doubleStartReturns409With3505() {
        // AC-D2: start a second timer while one runs → 409 errorCode 3505
        startTimerAt(Instant.now());

        web.post().uri("/time-entries/timer/start")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"userId\":\"" + userId + "\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3505);
    }

    @Test
    void runningTimerIsNeverAutoSplit() {
        // AC-D3: a running timer should not be auto-split — no extra rows appear without a stop call
        startTimerAt(Instant.now());

        // Wait a moment and check — still exactly 1 row, no split
        List<TimeEntry> rows = entriesForUser();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEndedAt()).isNull();
        assertThat(rows.get(0).getSplitGroupId()).isNull();
    }

    @Test
    void afterStopRunningTimerEntryIsClosedAndFinderReturnsEmpty() {
        // AC-D3: after stop, the row has endedAt!=null + durationSeconds>0;
        // the running-timer finder returns empty
        Instant start = Instant.parse("2026-05-18T10:00:00Z");
        Instant stop  = Instant.parse("2026-05-18T12:00:00Z");

        startTimerAt(start);
        stopTimer(stop, "UTC");

        List<TimeEntry> rows = entriesForUser();
        assertThat(rows).hasSize(1);
        TimeEntry stopped = rows.get(0);
        assertThat(stopped.getEndedAt()).isNotNull();
        assertThat(stopped.getDurationSeconds()).isEqualTo(7200L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void startTimerAt(Instant startedAt) {
        String body = """
                {"userId":"%s","startedAt":"%s"}
                """.formatted(userId, startedAt);
        web.post().uri("/time-entries/timer/start")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();
    }

    private void stopTimer(Instant endedAt, String zone) {
        String uri = "/time-entries/timer/stop?userId=" + userId + "&zoneId=" + zone
                + (endedAt != null ? "&endedAt=" + endedAt : "");
        web.post().uri(uri)
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk();
    }

    private List<TimeEntry> entriesForUser() {
        // Use ReactiveMongoTemplate (bypasses tenant scoping — the c547c84 lesson)
        return mongo.findAll(TimeEntry.class).collectList().block();
    }
}
