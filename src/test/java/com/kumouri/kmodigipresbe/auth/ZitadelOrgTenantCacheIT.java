package com.kumouri.kmodigipresbe.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * AC-6 (org→tenant cache + eviction on Tenant write) — {@code kmosf.auth.mode=zitadel}.
 *
 * <p>Resolve a token for {@code org-A} (populates the cache). Reassign the tenant's
 * {@code zitadelOrgId} to {@code org-B} via a repository save (which fires
 * {@code ZitadelOrgTenantCacheInvalidator}, a {@code ReactiveAfterSaveCallback<Tenant>},
 * clearing the whole map). A token for {@code org-B} now resolves, and a token for the
 * stale {@code org-A} now fails with 3301 — proving eviction-on-Tenant-write works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.auth.mode=zitadel"
})
class ZitadelOrgTenantCacheIT {

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
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("zc-" + tenantId)
                .displayName("Cache Eviction Tenant")
                .zitadelOrgId("org-A")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    @Test
    void tenantSaveEvictsOrgTenantCache() throws Exception {
        // 1. Resolve org-A → populates the cache.
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + tokenForOrg("org-A", "a@example.test"))
                .exchange()
                .expectStatus().isOk();

        // 2. Reassign the tenant to org-B (fires ZitadelOrgTenantCacheInvalidator).
        Tenant t = tenants.findById(tenantId).block();
        t.setZitadelOrgId("org-B");
        tenants.save(t).block();

        // 3. org-B now resolves (cache was cleared, re-read picks up the new mapping).
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + tokenForOrg("org-B", "b@example.test"))
                .exchange()
                .expectStatus().isOk();

        // 4. The stale org-A no longer maps to any tenant → 3301.
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + tokenForOrg("org-A", "a@example.test"))
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3301);
    }

    private String tokenForOrg(String orgId, String email) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(wireMock.baseUrl())
                .subject("zsub-" + orgId)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("urn:zitadel:iam:org:id", orgId)
                .claim("urn:zitadel:iam:org:project:roles", java.util.Map.of(
                        "staff", java.util.Map.of(orgId, "zitadel-" + orgId)))
                .claim("email", email)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key-1").build(),
                claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
