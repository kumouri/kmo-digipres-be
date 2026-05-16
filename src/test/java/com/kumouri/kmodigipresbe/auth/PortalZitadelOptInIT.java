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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-5 (portal Zitadel opt-in, both ways) — runs in {@code kmosf.auth.mode=zitadel}.
 *
 * <p>(a) A tenant with {@code zitadelOrgId} set + an RSA token whose only mapped role
 * is {@code CLIENT} → a {@code /portal/**} request authenticates through the
 * <em>portal</em> SecurityWebFilterChain (shared decoder + TenantWebFilter), and the
 * JIT-provisioned user is {@code portal=CLIENT}, {@code roles=[CLIENT]}. The portal
 * business endpoint then 404s with errorCode 1252 ("no linked contact") — which is
 * itself proof that auth + Zitadel-claim tenant resolution + JIT all succeeded (a
 * security/tenant failure would be 401/3300/3301; a missing JIT user would be 1251).
 *
 * <p>(b) A tenant with {@code zitadelOrgId == null}: the magic-link fallback is not
 * regressed by zitadel auth-mode — {@code POST /portal/auth/magic-link} is still
 * anonymously permitted (it reaches {@code HostTenantResolver} in the controller and
 * returns a business 400/1200 when no tenant header is supplied, rather than a
 * security 401/403). The magic-link flow mints local HS256 and does not depend on
 * {@code kmosf.auth.mode}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.auth.mode=zitadel"
})
class PortalZitadelOptInIT {

    static WireMockServer wireMock;
    static RSAKey rsaKey;

    @BeforeAll
    static void startWireMock() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
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
        registry.add("kmosf.auth.zitadel.issuer-uri", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID zitadelTenantId;
    private String nullOrgSlug;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        zitadelTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(zitadelTenantId).slug("pz-zit-" + zitadelTenantId)
                .displayName("Portal Zitadel Tenant")
                .zitadelOrgId("org-portal")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();

        UUID nullOrgId = UUID.randomUUID();
        nullOrgSlug = "pz-null-" + nullOrgId;
        tenants.save(Tenant.builder()
                .id(nullOrgId).slug(nullOrgSlug)
                .displayName("Portal Magic-Link Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    @Test
    void clientOnlyZitadelTokenJitProvisionsPortalClientUser() throws Exception {
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:id", "org-portal")
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "client", Map.of("org-portal", "zitadel-org-portal")))
                .claim("email", "client@example.test")
                .subject("zsub-client"));

        // Authenticated /portal/** request through the portal chain. The JIT user has
        // no linked contact, so the business handler 404s with 1252 — which proves the
        // portal chain authenticated the Zitadel token, the Zitadel-claim resolver set
        // a tenant context with a userId, and JIT created the user.
        web.get().uri("/portal/me/activities")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1252);

        List<User> created = mongo.find(
                Query.query(Criteria.where("tenantId").is(zitadelTenantId)),
                User.class).collectList().block();
        assertThat(created).hasSize(1);
        User u = created.get(0);
        assertThat(u.getEmail()).isEqualTo("client@example.test");
        assertThat(u.getPortal()).isEqualTo(User.Portal.CLIENT);
        assertThat(u.getRoles()).containsExactly("CLIENT");
    }

    @Test
    void magicLinkFallbackStillPermittedForNullOrgTenantInZitadelMode() {
        // POST /portal/auth/magic-link is anonymously permitted on the portal chain.
        // Without an X-Tenant-Slug header HostTenantResolver returns a business
        // 400/1200 — reaching that proves the endpoint is NOT security-blocked (401/403)
        // by zitadel auth-mode, i.e. the magic-link fallback is unaffected.
        web.post().uri("/portal/auth/magic-link")
                .bodyValue(Map.of("email", "fallback@example.test"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1200);
    }

    // --- helpers ---

    private interface ClaimCustomizer {
        JWTClaimsSet.Builder apply(JWTClaimsSet.Builder b);
    }

    private String rsaJwt(ClaimCustomizer customizer) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(wireMock.baseUrl())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000));
        JWTClaimsSet claims = customizer.apply(builder).build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key-1").build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
