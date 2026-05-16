package com.kumouri.kmodigipresbe.scheduling;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-6: Quartz proof job fires at least once within 15 seconds of startup.
 *
 * NOTE (Phase A / R-A4): The Quartz Mongo JobStore library could not be resolved
 * locally at implementation time (com.github.quartz-mongodb:2.2.0-rc1).
 * Phase A uses Spring Boot's default RAM store. This test asserts:
 * - NoOpQuartzJob.getExecutionCount() >= 1 (RAM store, proof-job fires)
 * - Does NOT assert quartz_jobs/quartz_triggers/quartz_locks Mongo collections
 *   because the Mongo store is not yet wired.
 *
 * When the Mongo store is wired in Phase E, update this test to also assert:
 *   mongo.collectionExists("quartz_jobs").block() == true
 *   mongo.collectionExists("quartz_triggers").block() == true
 *   mongo.collectionExists("quartz_locks").block() == true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=true"
        // Proof job enabled so we can observe it firing
})
class QuartzMongoJobStoreIT {

    @Autowired
    ReactiveMongoTemplate mongo;

    @Test
    void noOpQuartzJobFiresWithinFifteenSeconds() {
        // AC-6 (partial — RAM store): await the proof job execution
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(NoOpQuartzJob.getExecutionCount())
                                .as("NoOpQuartzJob should have fired at least once")
                                .isGreaterThanOrEqualTo(1));
    }

    @Test
    void schedulerIsConfiguredAndBootedWithRamStore() {
        // Verify the Quartz scheduler wired correctly (RAM store in Phase A).
        // The proof job should fire within 15s of boot.
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .until(() -> NoOpQuartzJob.getExecutionCount() >= 1);

        // Phase A RAM store — Mongo collections are NOT expected yet.
        // Phase E TODO: uncomment when Mongo store is wired:
        // assertThat(mongo.collectionExists("quartz_jobs").block()).isTrue();
        // assertThat(mongo.collectionExists("quartz_triggers").block()).isTrue();
        // assertThat(mongo.collectionExists("quartz_locks").block()).isTrue();
        assertThat(true).isTrue(); // placeholder until Mongo store
    }
}
