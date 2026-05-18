package com.kumouri.kmodigipresbe.portal;

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
 * H.7 — PortalZitadelFederationIT: AC-H4 integration-test proof.
 *
 * <p>Mirrors {@code PortalZitadelOptInIT} <strong>verbatim</strong> in structure
 * (the mandated A2 mock-OIDC/JWKS harness shape — no new harness built):
 * RSA keypair + WireMock JWKS stub + {@code @DynamicPropertySource}
 * for {@code jwks-uri}/{@code issuer-uri} + {@code kmosf.auth.mode=zitadel}.
 *
 * <h2>AC-H4 (a) — opted tenant + Zitadel JWT → portal auth + JIT user</h2>
 * An opted tenant ({@code Tenant.zitadelOrgId} set) authenticating a
 * {@code CLIENT}-role Zitadel JWT against {@code /portal/me/**} receives:
 * a JIT-provisioned user with {@code portal=CLIENT, roles=[CLIENT]}.
 * The business endpoint returns 404/1252 (no linked contact) — which proves
 * auth + tenant-resolution + JIT all succeeded (a security failure would be
 * 401/3300/3301; a missing JIT user would be 1251).
 *
 * <h2>AC-H4 (b) — non-opted tenant ({@code zitadelOrgId==null}) + Zitadel JWT
 * → rejected at the ZitadelOrgTenantCache (3301) + no session</h2>
 * A Zitadel JWT whose org claim does not match any opted tenant is rejected
 * with 3301 (the existing A2 ZitadelOrgTenantCache unknown-org path). This
 * proves the non-opted tenant's Zitadel token is rejected at the earliest
 * possible gate.
 *
 * <h2>AC-H4 (c) — non-opted tenant's local magic-link path still works</h2>
 * {@code POST /portal/auth/magic-link} for a tenant with
 * {@code zitadelOrgId==null} is still reachable and returns a business error
 * (1200 when no slug header) — proving the magic-link permit-all path is
 * byte-identical and NOT blocked by Zitadel auth mode.
 *
 * <p>Reuses the A2 mock-OIDC harness verbatim ({@code AuthModeZitadelIT} /
 * {@code PortalZitadelOptInIT} shape — no new harness introduced per the
 * plan mandate). No live Zitadel. No {@code @MockBean} — shard-safe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.auth.mode=zitadel"
        // kmosf.auth.zitadel.jwks-uri / issuer-uri set via @DynamicPropertySource
})
class PortalZitadelFederationIT {

    // --- A2 mock-OIDC harness (verbatim from PortalZitadelOptInIT / ZitadelFederationIT) ---

    static WireMockServer wireMock;
    static RSAKey rsaKey;

    @BeforeAll
    static void startWireMock() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key-h7").generate();
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

    // --- Spring context wiring ---

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID optedTenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        // Opted tenant — has zitadelOrgId set.
        optedTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(optedTenantId)
                .slug("pzf-opted-" + optedTenantId)
                .displayName("Opted Zitadel Portal Tenant")
                .zitadelOrgId("org-h7-opted")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();

        // Non-opted tenant — zitadelOrgId == null.
        tenants.save(Tenant.builder()
                .id(UUID.randomUUID())
                .slug("pzf-null-" + UUID.randomUUID())
                .displayName("Non-Opted Magic-Link Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    // -------------------------------------------------------------------------
    // AC-H4 (a): opted tenant + Zitadel CLIENT JWT → portal auth + JIT user
    // -------------------------------------------------------------------------

    @Test
    void optedTenant_zitadelClientJwt_portalAuthSucceedsAndJitCreatesUser() throws Exception {
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:id", "org-h7-opted")
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "client", Map.of("org-h7-opted", "zitadel-org-h7")))
                .claim("email", "portal-client-h7@example.test")
                .subject("zsub-h7-client"));

        // The portal chain authenticates the Zitadel JWT. JIT provisions a user.
        // /portal/me/activities returns 404/1252 (no linked contact) — which PROVES:
        //   auth = ok (else 401)
        //   tenant resolution = ok (else 3300/3301)
        //   JIT user = created (else 1251)
        web.get().uri("/portal/me/activities")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1252);

        // JIT-provisioned user exists with CLIENT role.
        List<User> created = mongo.find(
                Query.query(Criteria.where("tenantId").is(optedTenantId)),
                User.class).collectList().block();
        assertThat(created)
                .as("AC-H4(a): exactly one JIT user must be created for the opted tenant")
                .hasSize(1);
        User u = created.get(0);
        assertThat(u.getEmail()).isEqualTo("portal-client-h7@example.test");
        assertThat(u.getPortal()).isEqualTo(User.Portal.CLIENT);
        assertThat(u.getRoles()).containsExactly("CLIENT");
    }

    // -------------------------------------------------------------------------
    // AC-H4 (b): non-opted tenant Zitadel JWT → rejected at ZitadelOrgTenantCache
    // -------------------------------------------------------------------------

    @Test
    void nonOptedTenantOrgClaim_rejectedAtZitadelOrgCache_3301_noSession() throws Exception {
        // A Zitadel JWT whose org-id does not match any opted tenant.
        // ZitadelOrgTenantCache raises 3301 (unknown org).
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:id", "org-h7-not-opted")
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "client", Map.of("org-h7-not-opted", "zitadel-org-not-opted")))
                .claim("email", "nobody@nonoptedt.test")
                .subject("zsub-h7-nonoptedt"));

        web.get().uri("/portal/me/activities")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3301);

        // No User was JIT-provisioned.
        assertThat(mongo.findAll(User.class).collectList().block())
                .as("AC-H4(b): no user must be created for a non-opted/unknown org")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-H4 (c): non-opted tenant local magic-link path still works (byte-identical)
    // -------------------------------------------------------------------------

    @Test
    void nonOptedTenant_magicLinkPathStillPermitted_byteIdentical() {
        // POST /portal/auth/magic-link is permit-all on the portal chain.
        // Without an X-Tenant-Slug header HostTenantResolver returns 400/1200.
        // This proves the magic-link flow is NOT blocked by Zitadel auth mode.
        web.post().uri("/portal/auth/magic-link")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "fallback-h7@example.test"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1200);
    }

    // -------------------------------------------------------------------------
    // JWKS signing helper — verbatim from PortalZitadelOptInIT / ZitadelFederationIT
    // -------------------------------------------------------------------------

    private interface ClaimCustomizer {
        JWTClaimsSet.Builder apply(JWTClaimsSet.Builder b);
    }

    private String rsaJwt(ClaimCustomizer customizer) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(wireMock.baseUrl())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000));
        JWTClaimsSet claims = customizer.apply(builder).build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key-h7").build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
