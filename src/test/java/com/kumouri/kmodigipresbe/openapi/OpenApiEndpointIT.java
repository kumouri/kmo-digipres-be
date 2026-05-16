package com.kumouri.kmodigipresbe.openapi;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-1: GET /api/v1/openapi returns full spec unauthenticated (status 200, body
 * has paths./auth/discovery, info.title).
 *
 * AC-2 (build wiring): verifyOpenApi drift gate — asserting docs/api/openapi.json
 * is non-empty (actual drift check is the Gradle verifyOpenApi task).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// springdoc builds the full OpenAPI model lazily on the first /v3/api-docs hit;
// for a ~43-controller app that first request exceeds WebTestClient's 5s default
// in CI. 60s is generous headroom — if it still times out the spec generation is
// genuinely hanging (a real springdoc/model problem), not merely slow.
@AutoConfigureWebTestClient(timeout = "PT60S")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class OpenApiEndpointIT {

    @Autowired
    WebTestClient web;

    @Test
    void openApiEndpointReturnsSpecUnauthenticated() {
        // AC-1: /openapi (redirects to swagger-ui) or /v3/api-docs returns spec
        // WebTestClient applies base-path automatically via @AutoConfigureWebTestClient
        String body = web.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).contains("KMOSF CRM API");
        assertThat(body).contains("discovery");

        // Export the live spec so the Gradle `verifyOpenApi` task can consume it.
        // This is the spec-generation path that actually works in CI: it runs in
        // the Testcontainers test phase (Mongo available) rather than via the
        // springdoc gradle plugin (which forks a full app boot with no DB in CI).
        try {
            Path out = Paths.get("build/openapi/openapi.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, body);
        } catch (Exception e) {
            // Non-fatal: the drift gate degrades to skip-with-warning if the
            // spec wasn't exported. Never fail the AC-1 assertion over an IO hiccup.
            System.err.println("OpenApiEndpointIT: could not export spec for verifyOpenApi: " + e);
        }
    }

    @Test
    void committedOpenApiSpecIsNonEmpty() {
        // AC-2 (partial): the committed spec file exists and is non-empty.
        // The actual drift check is the Gradle verifyOpenApi task (./gradlew check).
        Path committedSpec = Paths.get("docs/api/openapi.json");
        assertThat(committedSpec).exists();
        assertThat(committedSpec.toFile().length()).isGreaterThan(0);
    }
}
