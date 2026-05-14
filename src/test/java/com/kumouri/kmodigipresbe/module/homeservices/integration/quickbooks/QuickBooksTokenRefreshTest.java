package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 10d — when the connection's {@code tokenExpiresAt} falls within the
 * configured skew window, {@code refreshIfNeeded} POSTs the refresh-token grant
 * and rotates {@code accessToken} / {@code refreshToken} on the persisted
 * connection. When the token is still well within its TTL, no HTTP call is made.
 */
class QuickBooksTokenRefreshTest {

    private WireMockServer wireMock;
    private IntegrationConnectionRepository connections;
    private QuickBooksOAuthService oauth;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        connections = mock(IntegrationConnectionRepository.class);
        QuickBooksProperties props = new QuickBooksProperties();
        props.setEnabled(true);
        props.setClientId("c");
        props.setClientSecret("s");
        props.setRedirectUri("http://localhost/cb");
        props.setOauthBaseUrl(wireMock.baseUrl());
        props.setApiBaseUrl(wireMock.baseUrl());
        props.setStateSigningSecret("test-secret");
        props.setRefreshSkewSeconds(300);
        oauth = new QuickBooksOAuthService(
                props, connections, WebClient.builder(), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void tokenAboutToExpire_isRotated() {
        UUID tenantId = UUID.randomUUID();
        IntegrationConnection conn = IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("quickbooks")
                .secrets(new HashMap<>(Map.of(
                        "accessToken", "old-access",
                        "refreshToken", "old-refresh",
                        "tokenExpiresAt", Instant.now().plusSeconds(60).toString())))
                .config(new HashMap<>(Map.of("realmId", "9999")))
                .build();
        when(connections.save(any(IntegrationConnection.class)))
                .thenAnswer(inv -> Mono.just((IntegrationConnection) inv.getArgument(0)));

        wireMock.stubFor(post(urlPathEqualTo("/oauth2/v1/tokens/bearer"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"new-access\","
                                + "\"refresh_token\":\"new-refresh\","
                                + "\"expires_in\":3600}")));

        IntegrationConnection refreshed = oauth.refreshIfNeeded(conn).block();
        assertThat(refreshed).isNotNull();
        assertThat(refreshed.getSecrets()).containsEntry("accessToken", "new-access");
        assertThat(refreshed.getSecrets()).containsEntry("refreshToken", "new-refresh");
        Instant newExpiry = Instant.parse(refreshed.getSecrets().get("tokenExpiresAt"));
        assertThat(newExpiry).isAfter(Instant.now().plusSeconds(60));
    }

    @Test
    void tokenWellWithinTtl_isNotRefreshed() {
        UUID tenantId = UUID.randomUUID();
        IntegrationConnection conn = IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("quickbooks")
                .secrets(new HashMap<>(Map.of(
                        "accessToken", "still-fresh",
                        "refreshToken", "rt",
                        "tokenExpiresAt", Instant.now().plusSeconds(3600).toString())))
                .config(new HashMap<>(Map.of("realmId", "9999")))
                .build();

        IntegrationConnection result = oauth.refreshIfNeeded(conn).block();
        assertThat(result).isNotNull();
        assertThat(result.getSecrets()).containsEntry("accessToken", "still-fresh");
        // No HTTP call to the token endpoint.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void exchangeAuthCode_persistsConnection() {
        UUID tenantId = UUID.randomUUID();
        // No existing connection — exercises the "fresh" branch.
        when(connections.findByTenantIdAndProvider(eq(tenantId), eq("quickbooks")))
                .thenReturn(Mono.empty());
        when(connections.save(any(IntegrationConnection.class)))
                .thenAnswer(inv -> Mono.just((IntegrationConnection) inv.getArgument(0)));

        wireMock.stubFor(post(urlPathEqualTo("/oauth2/v1/tokens/bearer"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"a\",\"refresh_token\":\"r\","
                                + "\"expires_in\":3600}")));

        String state = oauth.signState(tenantId, Instant.now().getEpochSecond() + 300);

        StepVerifier.create(oauth.exchangeAuthCode("auth-code-abc", state, "realm-77"))
                .assertNext(conn -> {
                    assertThat(conn.getTenantId()).isEqualTo(tenantId);
                    assertThat(conn.getProvider()).isEqualTo("quickbooks");
                    assertThat(conn.getSecrets()).containsEntry("accessToken", "a");
                    assertThat(conn.getSecrets()).containsEntry("refreshToken", "r");
                    assertThat(conn.getSecrets()).containsKey("webhookVerifierToken");
                    assertThat(conn.getConfig()).containsEntry("realmId", "realm-77");
                })
                .verifyComplete();
    }

    @Test
    void exchangeAuthCode_tamperedState_isRejected() {
        UUID tenantId = UUID.randomUUID();
        String state = oauth.signState(tenantId, Instant.now().getEpochSecond() + 300);
        // Flip one character in the signature segment.
        int dot = state.indexOf('.');
        String tampered = state.substring(0, dot + 1)
                + (state.charAt(dot + 1) == 'A' ? 'B' : 'A')
                + state.substring(dot + 2);

        StepVerifier.create(oauth.exchangeAuthCode("auth-code", tampered, "realm-1"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(
                            com.kumouri.kmodigipresbe.exceptions.DigiPresBeException.class);
                    assertThat(((com.kumouri.kmodigipresbe.exceptions.DigiPresBeException) err)
                            .getErrorCode()).isEqualTo(2812);
                })
                .verify();
    }
}
