package com.kumouri.kmodigipresbe.integration.gbp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link GbpTokenService} (no Docker / no Spring context — the
 * {@code QuickBooksTokenRefreshTest} pattern). WireMock serves the OAuth2 token endpoint; the
 * {@link IntegrationConnectionRepository} is a Mockito mock that echoes the saved connection.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li><strong>happy path:</strong> a valid {@code refreshToken} → the token endpoint serves a new
 *       {@code access_token} → the rotated {@code accessToken}/{@code refreshToken}/{@code tokenExpiresAt}
 *       are persisted and the new access token is emitted; the form body carried
 *       {@code grant_type=refresh_token} + the client credentials;</li>
 *   <li><strong>no refreshToken → 4034</strong> with no HTTP call;</li>
 *   <li><strong>token endpoint 400 → 4034</strong> (non-2xx);</li>
 *   <li><strong>response without access_token → 4034.</strong></li>
 * </ul>
 */
class GbpTokenServiceTest {

    private WireMockServer wireMock;
    private IntegrationConnectionRepository connections;
    private GbpTokenService tokenService;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        connections = mock(IntegrationConnectionRepository.class);
        GbpProperties props = new GbpProperties();
        props.setTokenUrl(wireMock.baseUrl() + "/token");
        props.setClientId("c-id");
        props.setClientSecret("c-secret");
        tokenService = new GbpTokenService(
                connections, WebClient.builder(), new ObjectMapper(), props);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private IntegrationConnection conn(Map<String, String> secrets) {
        return IntegrationConnection.builder()
                .tenantId(UUID.randomUUID())
                .provider("google-business")
                .secrets(new HashMap<>(secrets))
                .build();
    }

    @Test
    void validRefreshToken_isExchangedAndPersisted() {
        IntegrationConnection conn = conn(Map.of(
                "accessToken", "old-access",
                "refreshToken", "old-refresh"));
        when(connections.save(any(IntegrationConnection.class)))
                .thenAnswer(inv -> Mono.just((IntegrationConnection) inv.getArgument(0)));

        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"new-access\","
                                + "\"refresh_token\":\"new-refresh\",\"expires_in\":3600}")));

        StepVerifier.create(tokenService.refreshAccessToken(conn))
                .assertNext(newToken -> assertThat(newToken).isEqualTo("new-access"))
                .verifyComplete();

        // The rotation was applied to the connection that was saved.
        assertThat(conn.getSecrets()).containsEntry("accessToken", "new-access");
        assertThat(conn.getSecrets()).containsEntry("refreshToken", "new-refresh");
        Instant newExpiry = Instant.parse(conn.getSecrets().get("tokenExpiresAt"));
        assertThat(newExpiry).isAfter(Instant.now().plusSeconds(60));

        // The form body carried the refresh grant + the OAuth-app client credentials.
        wireMock.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/token"))
                .withRequestBody(WireMock.containing("grant_type=refresh_token"))
                .withRequestBody(WireMock.containing("refresh_token=old-refresh"))
                .withRequestBody(WireMock.containing("client_id=c-id"))
                .withRequestBody(WireMock.containing("client_secret=c-secret")));
    }

    @Test
    void rotatedRefreshTokenAbsent_keepsExistingRefreshToken() {
        IntegrationConnection conn = conn(Map.of(
                "accessToken", "old-access",
                "refreshToken", "keep-me"));
        when(connections.save(any(IntegrationConnection.class)))
                .thenAnswer(inv -> Mono.just((IntegrationConnection) inv.getArgument(0)));

        // Google often does NOT return a new refresh_token on a refresh-grant.
        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"rotated-access\",\"expires_in\":3599}")));

        StepVerifier.create(tokenService.refreshAccessToken(conn))
                .assertNext(t -> assertThat(t).isEqualTo("rotated-access"))
                .verifyComplete();

        assertThat(conn.getSecrets()).containsEntry("accessToken", "rotated-access");
        // The original refresh token is retained when none is returned.
        assertThat(conn.getSecrets()).containsEntry("refreshToken", "keep-me");
    }

    @Test
    void noRefreshToken_surfaces4034_noHttpCall() {
        IntegrationConnection conn = conn(Map.of("accessToken", "old-access"));

        StepVerifier.create(tokenService.refreshAccessToken(conn))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(4034);
                })
                .verify();

        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void tokenEndpointNon2xx_surfaces4034() {
        IntegrationConnection conn = conn(Map.of(
                "accessToken", "old-access",
                "refreshToken", "rt"));
        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\"}")));

        StepVerifier.create(tokenService.refreshAccessToken(conn))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(4034);
                })
                .verify();
    }

    @Test
    void responseWithoutAccessToken_surfaces4034() {
        IntegrationConnection conn = conn(Map.of(
                "accessToken", "old-access",
                "refreshToken", "rt"));
        wireMock.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        StepVerifier.create(tokenService.refreshAccessToken(conn))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(4034);
                })
                .verify();
    }
}
