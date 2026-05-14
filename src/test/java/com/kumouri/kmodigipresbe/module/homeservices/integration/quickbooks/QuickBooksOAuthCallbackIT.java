package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10d — Intuit's redirect lands on
 * {@code /public/integrations/quickbooks/oauth/callback?code=...&state=...&realmId=...}
 * (anonymous; signed {@code state} is the tenant binding). On success an
 * {@link IntegrationConnection} for provider {@code quickbooks} is upserted;
 * a bad state signature returns 401 with errorCode 2812 / 2810.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class QuickBooksOAuthCallbackIT {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void wireMockProps(DynamicPropertyRegistry registry) {
        if (!WIRE_MOCK.isRunning()) WIRE_MOCK.start();
        registry.add("kmosf.integrations.quickbooks.enabled", () -> "true");
        registry.add("kmosf.integrations.quickbooks.client-id", () -> "test-client-id");
        registry.add("kmosf.integrations.quickbooks.client-secret", () -> "test-client-secret");
        registry.add("kmosf.integrations.quickbooks.redirect-uri",
                () -> "http://localhost/callback");
        registry.add("kmosf.integrations.quickbooks.oauth-base-url", WIRE_MOCK::baseUrl);
        registry.add("kmosf.integrations.quickbooks.api-base-url", WIRE_MOCK::baseUrl);
        registry.add("kmosf.integrations.quickbooks.state-signing-secret",
                () -> "test-state-signing-secret");
    }

    @Autowired WebTestClient web;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired QuickBooksOAuthService oauth;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), IntegrationConnection.class).block();
        WIRE_MOCK.resetAll();
    }

    @AfterEach
    void after() {
        WIRE_MOCK.resetAll();
    }

    @Test
    void validSignedState_upsertsConnection() {
        UUID tenantId = UUID.randomUUID();
        String state = oauth.signState(tenantId, Instant.now().getEpochSecond() + 300);
        String realmId = "1234567890";

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/oauth2/v1/tokens/bearer"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"acc\",\"refresh_token\":\"ref\","
                                + "\"expires_in\":3600,\"token_type\":\"bearer\"}")));

        web.get().uri(uri -> uri.path("/public/integrations/quickbooks/oauth/callback")
                        .queryParam("code", "auth-code-abc")
                        .queryParam("state", state)
                        .queryParam("realmId", realmId)
                        .build())
                .exchange()
                .expectStatus().isOk();

        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        IntegrationConnection persisted = connections.findByTenantIdAndProvider(tenantId, "quickbooks")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getSecrets()).containsEntry("accessToken", "acc");
        assertThat(persisted.getSecrets()).containsEntry("refreshToken", "ref");
        assertThat(persisted.getSecrets()).containsKey("webhookVerifierToken");
        assertThat(persisted.getConfig()).containsEntry("realmId", realmId);
    }

    @Test
    void tamperedState_returns401_errorCode2812() {
        UUID tenantId = UUID.randomUUID();
        String state = oauth.signState(tenantId, Instant.now().getEpochSecond() + 300);
        int dot = state.indexOf('.');
        String tampered = state.substring(0, dot + 1)
                + (state.charAt(dot + 1) == 'A' ? 'B' : 'A')
                + state.substring(dot + 2);

        web.get().uri(uri -> uri.path("/public/integrations/quickbooks/oauth/callback")
                        .queryParam("code", "auth-code")
                        .queryParam("state", tampered)
                        .queryParam("realmId", "9999")
                        .build())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2812);
    }

    @Test
    void missingState_returns400_errorCode2810() {
        web.get().uri(uri -> uri.path("/public/integrations/quickbooks/oauth/callback")
                        .queryParam("code", "auth-code")
                        .queryParam("realmId", "9999")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2810);
    }
}
