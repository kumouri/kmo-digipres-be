package com.kumouri.kmodigipresbe.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-3 (login mode-switch, zitadel half): in {@code kmosf.auth.mode=zitadel},
 * {@code POST /auth/login} → 410 with errorCode 3303, and {@code /auth/discovery}
 * returns {@code mode=zitadel} with a non-blank {@code authorizeUrl}.
 *
 * <p>The <strong>local</strong> half of the AC-3 regression pair is the existing
 * {@code AuthModeLocalIT} (kept byte-identical per the Phase A2 byte-identical-local
 * invariant): it already asserts {@code POST /auth/login} → 200 + token (its
 * {@code login()} seed helper / {@code localTokenIsAcceptedOnProtectedEndpoint}) and
 * {@code /auth/discovery} → {@code mode=local} + {@code loginPath}
 * ({@code discoveryEndpointReturnsModeLocal}). Together they are the login-behaviour
 * regression guard without modifying the byte-identical-local proof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.auth.mode=zitadel"
        // kmosf.auth.zitadel.jwks-uri / issuer-uri set via @DynamicPropertySource
})
class AuthLoginModeIT {

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[]}")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.auth.zitadel.jwks-uri",
                () -> wireMock.baseUrl() + "/.well-known/jwks.json");
        registry.add("kmosf.auth.zitadel.issuer-uri", () -> wireMock.baseUrl());
    }

    @Autowired
    WebTestClient web;

    @Test
    void passwordLoginIsGoneInZitadelMode() {
        // Body is a syntactically valid LoginRequest (passes @Valid bean validation)
        // so the request reaches the controller's mode check rather than 400-ing on
        // validation. Zitadel mode → 410 Gone, errorCode 3303.
        web.post().uri("/auth/login")
                .bodyValue(Map.of("email", "anyone@example.test", "password", "irrelevant"))
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3303);
    }

    @Test
    void discoveryReturnsZitadelModeAndAuthorizeUrl() {
        web.get().uri("/auth/discovery")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result -> {
                    Map<?, ?> body = result.getResponseBody();
                    assertThat(body).isNotNull();
                    assertThat(body.get("mode")).isEqualTo("zitadel");
                    Object authorizeUrl = body.get("authorizeUrl");
                    assertThat(authorizeUrl).isInstanceOf(String.class);
                    assertThat((String) authorizeUrl).isNotBlank();
                    assertThat((String) authorizeUrl).endsWith("/oauth/v2/authorize");
                    // loginPath retained in both modes for local-mode FE compatibility.
                    assertThat(body.get("loginPath")).isEqualTo("/auth/login");
                });
    }
}
