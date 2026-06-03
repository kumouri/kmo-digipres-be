package com.kumouri.kmodigipresbe.integration.gbp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GBP OAuth2 access-token refresh — GbpTokenRefreshIT: the headline IT for the 401→refresh→retry
 * path. Drives {@code GbpApiClient.fetchReviews()} directly under a synthetic tenant context (the
 * {@code GbpReplyDraftServiceIT} pattern). ONE WireMock server serves both the GBP API
 * ({@code kmosf.gbp.api-base-url}) and the OAuth2 token endpoint ({@code kmosf.gbp.token-url}) on
 * disjoint paths ({@code GET /v4/reviews} vs {@code POST /token}), both pointed at it via
 * {@code @DynamicPropertySource}.
 *
 * <h2>§7 no-live-Google</h2>
 * Both the GBP base URL and the token URL resolve to WireMock; the OAuth {@code accessToken} /
 * {@code refreshToken} and the {@code clientId}/{@code clientSecret} are sandbox fakes — no live
 * Google, no host hardcoded.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li><strong>401-then-200:</strong> WireMock GBP returns 401 the first time and 200 the second
 *       (a stateful scenario); the token endpoint serves a fresh {@code access_token}. The original
 *       {@code fetchReviews()} succeeds after <em>exactly one</em> refresh + retry, the token
 *       endpoint was hit exactly once, the GBP endpoint exactly twice, AND the new {@code accessToken}
 *       (with the Bearer the retry carried) is persisted to the IntegrationConnection.</li>
 *   <li><strong>refresh-fails → 4034:</strong> GBP returns 401; the token endpoint returns 500 →
 *       {@code DigiPresBeException(4034)} (gbp-token-refresh-failed), no successful retry.</li>
 *   <li><strong>no-refreshToken → 4034:</strong> GBP returns 401 but the connection carries no
 *       {@code refreshToken} → {@code 4034} without any token-endpoint call (a re-consent is
 *       required).</li>
 * </ul>
 *
 * <p>Shard-safe: WireMock via {@code @DynamicPropertySource}; self-clean {@code mongo.remove}
 * {@code @BeforeEach}; no {@code application-test.properties} / {@code build.gradle} shard change.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // KMOSF-global OAuth app credentials (sandbox fakes — §7).
        "kmosf.gbp.client-id=test-gbp-client-id",
        "kmosf.gbp.client-secret=test-gbp-client-secret"
})
class GbpTokenRefreshIT {

    private static final String STALE_ACCESS_TOKEN = "ya29.gbp-STALE-access-token";
    private static final String FRESH_ACCESS_TOKEN = "ya29.gbp-FRESH-access-token";
    private static final String REFRESH_TOKEN = "1//gbp-test-refresh-token";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void gbpProps(DynamicPropertyRegistry registry) {
        // GBP API root + the OAuth2 token endpoint both resolve to the one WireMock server;
        // disjoint paths (/v4/... vs /token).
        registry.add("kmosf.gbp.api-base-url", () -> wireMock.baseUrl());
        registry.add("kmosf.gbp.token-url", () -> wireMock.baseUrl() + "/token");
    }

    @Autowired GbpApiClient apiClient;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_GBP_REVIEWS"));
        tenants.save(Tenant.builder()
                .id(tenantId).slug("gbp-token-it-" + tenantId)
                .displayName("GBP Token IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
    }

    private void saveConnection(boolean withRefreshToken) {
        Map<String, String> secrets = new HashMap<>();
        secrets.put("accessToken", STALE_ACCESS_TOKEN);
        if (withRefreshToken) {
            secrets.put("refreshToken", REFRESH_TOKEN);
        }
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("google-business")
                .secrets(secrets)
                .build()).block();
    }

    /** GBP GET: 401 with the stale Bearer, then 200 with the fresh Bearer (stateful scenario). */
    private void stubGbp401Then200() {
        String reviews = "{\"reviews\":["
                + "{\"reviewId\":\"reviews/r1\",\"starRating\":\"FIVE\","
                + "\"comment\":\"Great service\",\"reviewer\":{\"displayName\":\"Jane\"},"
                + "\"createTime\":\"2026-05-01T10:00:00Z\"}]}";
        wireMock.stubFor(get(urlPathEqualTo("/v4/reviews"))
                .inScenario("token-refresh")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}"))
                .willSetStateTo("refreshed"));
        wireMock.stubFor(get(urlPathEqualTo("/v4/reviews"))
                .inScenario("token-refresh")
                .whenScenarioStateIs("refreshed")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(reviews)));
    }

    private void stubTokenEndpoint200() {
        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"" + FRESH_ACCESS_TOKEN + "\","
                                + "\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    // -------------------------------------------------------------------------
    // 401 -> refresh -> retry-once -> success; new token persisted
    // -------------------------------------------------------------------------

    @Test
    void fetch401_refreshesAndRetriesOnce_persistsNewToken() {
        saveConnection(true);
        stubGbp401Then200();
        stubTokenEndpoint200();

        List<GbpReview> reviews = apiClient.fetchReviews()
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // The original call succeeded after the refresh+retry.
        assertThat(reviews).isNotNull();
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).reviewId()).isEqualTo("reviews/r1");

        // Exactly ONE refresh (token endpoint), and the GBP endpoint hit exactly TWICE
        // (the initial 401 + the retry).
        wireMock.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/token"))
                .withRequestBody(WireMock.containing("grant_type=refresh_token"))
                .withRequestBody(WireMock.containing("refresh_token="))
                .withRequestBody(WireMock.containing("client_id=test-gbp-client-id")));
        wireMock.verify(2, WireMock.getRequestedFor(urlPathEqualTo("/v4/reviews")));
        // The retry carried the FRESH Bearer (proves the new token was actually used).
        wireMock.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/v4/reviews"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + FRESH_ACCESS_TOKEN)));

        // The rotated accessToken was persisted to the IntegrationConnection.
        IntegrationConnection persisted =
                connections.findByTenantIdAndProvider(tenantId, "google-business")
                        .contextWrite(TenantContextHolder.write(ctx))
                        .block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getSecrets()).containsEntry("accessToken", FRESH_ACCESS_TOKEN);
        // The refresh token is retained, and an expiry was stamped.
        assertThat(persisted.getSecrets()).containsEntry("refreshToken", REFRESH_TOKEN);
        assertThat(persisted.getSecrets()).containsKey("tokenExpiresAt");
    }

    // -------------------------------------------------------------------------
    // 401 -> token endpoint fails -> 4034 (gbp-token-refresh-failed)
    // -------------------------------------------------------------------------

    @Test
    void fetch401_tokenEndpoint500_surfaces4034() {
        saveConnection(true);
        // GBP keeps returning 401 (only the STARTED arm is needed — we never reach 'refreshed').
        wireMock.stubFor(get(urlPathEqualTo("/v4/reviews"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":401}}")));
        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"server_error\"}")));

        StepVerifier.create(apiClient.fetchReviews()
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(4034);
                })
                .verify();

        // The refresh was attempted exactly once; the original GBP call was NOT retried after the
        // failed refresh (still just the one initial GET).
        wireMock.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/token")));
        wireMock.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/v4/reviews")));
    }

    // -------------------------------------------------------------------------
    // 401 -> no refreshToken on the connection -> 4034, no token-endpoint call
    // -------------------------------------------------------------------------

    @Test
    void fetch401_noRefreshToken_surfaces4034_noTokenCall() {
        saveConnection(false);   // accessToken but NO refreshToken
        wireMock.stubFor(get(urlPathEqualTo("/v4/reviews"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":401}}")));

        StepVerifier.create(apiClient.fetchReviews()
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(4034);
                })
                .verify();

        // No token-endpoint call at all (cannot refresh without a refresh token).
        wireMock.verify(0, WireMock.postRequestedFor(urlPathEqualTo("/token")));
    }
}
