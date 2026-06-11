package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

/**
 * Security fix AI-01 — {@link PublicContactRateLimitFilter} must throttle the five public AI-vision /
 * intake endpoints (styleconsult/consult, stylermatch/match, home-services equipment-photo/upload,
 * quoting/quote, mole-tripwire/report), keyed by IP + the widget {@code {token}} path segment (the BE-11
 * mole-triage approach). The cap is {@code MAX_REQUESTS} (10) per {@code WINDOW_SECONDS} (60) — the 11th
 * rapid request from the same (IP, token) is a {@code 429}.
 *
 * <p>The filter runs at {@code HIGHEST_PRECEDENCE + 100} — before routing — so it counts a request and
 * decides 429 purely from the path, independent of whether the target module is enabled or the token is
 * valid. We therefore drive it with the AI modules OFF: each pre-limit request 404s (the module-gate
 * "disabled module → endpoint not registered" outcome — see {@code StyleConsultModuleGateIT}), which is
 * the point — a 404 is NOT a 429, and the filter still counts it. Each test uses a unique token so its
 * per-(IP, token) bucket is isolated from sibling tests sharing this cached filter bean.
 *
 * <p>Bucket storage is the filter's in-process map; tokens here are fresh UUIDs so no cross-test bleed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        // All five AI-intake modules OFF → every route 404s pre-limit (a clean non-429 the filter counts).
        "kmosf.modules.chairfill.enabled=false",
        "kmosf.modules.salon-spa.enabled=false",
        "kmosf.modules.home-services.enabled=false",
        "kmosf.modules.quoting.enabled=false",
        "kmosf.modules.mole-tripwire.enabled=false",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class PublicAiIntakeRateLimitIT {

    /** Mirrors {@link PublicContactRateLimitFilter#MAX_REQUESTS} (package-private constant). */
    private static final int MAX_REQUESTS = 10;

    @Autowired WebTestClient web;

    private String consultUri(String token) {
        return "/public/integrations/styleconsult/" + token + "/consult";
    }

    private WebTestClient.ResponseSpec postConsult(String token) {
        // A trivial multipart body — the request never reaches the (absent) controller; the filter
        // decides on the path alone. Content-Type set so WebFlux doesn't 415 before the filter… which
        // it wouldn't anyway (the filter precedes routing), but this keeps the request well-formed.
        return web.post().uri(consultUri(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange();
    }

    @Test
    void eleventhRapidRequestToAPublicAiRoute_is429() {
        String token = "rl-consult-" + UUID.randomUUID();

        // The first MAX_REQUESTS (10) are counted but pass the filter (they 404 — module off).
        for (int i = 0; i < MAX_REQUESTS; i++) {
            postConsult(token).expectStatus().value(s ->
                    org.assertj.core.api.Assertions.assertThat(s).isNotEqualTo(429));
        }
        // The 11th from the same (IP, token) trips the limiter.
        postConsult(token).expectStatus().isEqualTo(429);
    }

    @Test
    void aFreshTokenGetsItsOwnQuota_noCrossTokenBleed() {
        String tokenA = "rl-a-" + UUID.randomUUID();
        String tokenB = "rl-b-" + UUID.randomUUID();

        // Exhaust token A.
        for (int i = 0; i < MAX_REQUESTS; i++) {
            postConsult(tokenA);
        }
        postConsult(tokenA).expectStatus().isEqualTo(429);

        // Token B is a different bucket — its first request must NOT be throttled.
        postConsult(tokenB).expectStatus().value(s ->
                org.assertj.core.api.Assertions.assertThat(s).isNotEqualTo(429));
    }

    @Test
    void allFiveAiRoutesShareOneBucketPerToken_alternatingCannotDoubleQuota() {
        // One token, requests spread across all five AI-intake routes. They share a single per-(IP,
        // token) bucket on purpose (the lead-capture-bucket rationale), so an attacker cannot double the
        // effective quota by alternating routes. 10 mixed requests are fine; the 11th — on any route —
        // is a 429.
        String token = "rl-mixed-" + UUID.randomUUID();
        String[] routes = {
                "/public/integrations/styleconsult/" + token + "/consult",
                "/public/integrations/stylermatch/" + token + "/match",
                "/public/integrations/home-services/equipment-photo/" + token + "/upload",
                "/public/integrations/quoting/" + token + "/quote",
                "/public/integrations/mole-tripwire/" + token + "/report"
        };

        for (int i = 0; i < MAX_REQUESTS; i++) {
            String uri = routes[i % routes.length];
            web.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().value(s ->
                            org.assertj.core.api.Assertions.assertThat(s).isNotEqualTo(429));
        }
        // 11th overall, on the (different) report route — still 429: the bucket is shared.
        web.post().uri(routes[4]).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429);
    }
}
