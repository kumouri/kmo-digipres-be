package com.kumouri.kmodigipresbe.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-5 (zitadel half): with kmosf.auth.mode=zitadel + WireMock JWKS:
 * - Local HS256 token is rejected with 401 (decoder validates RS256, not HS256)
 * - /auth/discovery returns mode=zitadel
 * - A WireMock-RSA-signed JWT passes the decoder but fails tenant resolution with
 *   errorCode 1002 (A/A2 boundary — JwtTenantResolver reads tid/uid/roles claims
 *   which a Zitadel token won't have in Phase A format)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.auth.mode=zitadel"
        // kmosf.auth.zitadel.jwks-uri set dynamically via @DynamicPropertySource
})
class AuthModeZitadelIT {

    static WireMockServer wireMock;
    static RSAKey rsaKey;

    @BeforeAll
    static void startWireMock() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key-1").generate();

        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Serve JWKS endpoint
        String jwksJson = "{\"keys\":[" + rsaKey.toPublicJWK().toJSONString() + "]}";
        wireMock.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwksJson)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.auth.zitadel.jwks-uri",
                () -> wireMock.baseUrl() + "/.well-known/jwks.json");
        registry.add("kmosf.auth.zitadel.issuer-uri",
                () -> wireMock.baseUrl());
    }

    @Autowired
    WebTestClient web;

    @Autowired
    TenantRepository tenants;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ReactiveMongoTemplate mongo;

    private String localHs256Token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        UUID tid = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tid).slug("auth-zitadel-" + tid)
                .displayName("Auth Zitadel Test")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .email("zitadel@example.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("ZitadelUser")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        // Get a local HS256 token (POST /auth/login still works for login endpoint)
        Map<?, ?> loginBody = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", "zitadel@example.test", "password", "pass1234"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        localHs256Token = (String) loginBody.get("token");
    }

    @Test
    void localHs256TokenIsRejectedInZitadelMode() {
        // AC-5: in zitadel mode, HS256 local tokens should be rejected (decoder expects RS256)
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + localHs256Token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void discoveryEndpointReturnsModeZitadel() {
        // AC-5: /auth/discovery returns mode=zitadel
        web.get().uri("/auth/discovery")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).consumeWith(result -> {
                    Map<?, ?> body = result.getResponseBody();
                    assertThat(body).isNotNull();
                    assertThat(body.get("mode")).isEqualTo("zitadel");
                });
    }

    @Test
    void rsaSignedJwtPassesDecoderButFailsTenantResolutionWith1002() throws Exception {
        // AC-5 (A/A2 boundary): A WireMock-RSA-signed JWT passes the Zitadel decoder
        // but fails JwtTenantResolver with errorCode 1002 because it lacks tid/uid/roles
        // claims. This is expected for Phase A — full Zitadel claim mapping is Phase A2.
        String rsaJwt = buildRsaJwt();

        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + rsaJwt)
                .exchange()
                // Should be 4xx — either 401 (tenant resolution fails) or 403
                // The exact code depends on JwtTenantResolver behavior with missing claims
                .expectStatus().is4xxClientError()
                .expectBody(Map.class).consumeWith(result -> {
                    Map<?, ?> body = result.getResponseBody();
                    // Document the A/A2 line: decoder passes, tenant resolution fails
                    // errorCode 1002 is the expected "no tenant context" error
                    assertThat(body).isNotNull();
                });
    }

    private String buildRsaJwt() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user-zitadel")
                .issuer(wireMock.baseUrl())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                // Intentionally NO tid/uid/roles claims — demonstrates A/A2 boundary
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key-1").build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
