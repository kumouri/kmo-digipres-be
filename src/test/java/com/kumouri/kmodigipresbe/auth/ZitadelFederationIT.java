package com.kumouri.kmodigipresbe.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.audit.AuditOp;
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
 * AC-1 + AC-2 (Phase A2 Zitadel happy path + foreign/missing claims).
 *
 * <p>Reuses the {@code AuthModeZitadelIT} WireMock-fake-Zitadel scaffold: an RSA
 * keypair, a stubbed {@code /.well-known/jwks.json}, {@code @DynamicPropertySource}
 * for {@code jwks-uri}/{@code issuer-uri}. RSA tokens are signed in-test with
 * Zitadel-shaped claims (org id, project-roles as a JSON <em>object</em>, email, sub).
 * No real Zitadel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.auth.mode=zitadel"
        // kmosf.auth.zitadel.jwks-uri / issuer-uri set via @DynamicPropertySource
})
class ZitadelFederationIT {

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

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), AuditEvent.class).block();

        tenantId = UUID.randomUUID();
        // A second null-org tenant proves the sparse unique index tolerates many nulls.
        tenants.save(Tenant.builder()
                .id(UUID.randomUUID()).slug("zf-nullorg-" + UUID.randomUUID())
                .displayName("No Zitadel Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("zf-" + tenantId)
                .displayName("Zitadel Federated Tenant")
                .zitadelOrgId("org-123")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    @Test
    void validTokenResolvesTenantRolesAndJitCreatesUser() throws Exception {
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:id", "org-123")
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "staff", Map.of("org-123", "zitadel-org-123"),
                        "admin", Map.of("org-123", "zitadel-org-123")))
                .claim("email", "jit@example.test")
                .subject("zsub-1"));

        // First request → JIT-provision + 200.
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk();

        List<User> after = mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId)), User.class).collectList().block();
        assertThat(after).hasSize(1);
        User u = after.get(0);
        assertThat(u.getEmail()).isEqualTo("jit@example.test");
        assertThat(u.getPortal()).isEqualTo(User.Portal.STAFF);
        assertThat(u.getRoles()).contains("STAFF", "ADMIN");
        assertThat(u.getStatus()).isEqualTo(User.UserStatus.ACTIVE);
        UUID jitUserId = u.getId();

        // Second identical request → idempotent: still 200, still exactly one row.
        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk();

        List<User> after2 = mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId)), User.class).collectList().block();
        assertThat(after2).hasSize(1);
        assertThat(after2.get(0).getId()).isEqualTo(jitUserId);

        // A CREATE audit event for the JIT User exists.
        List<AuditEvent> events = mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("entityType").is("User")
                        .and("entityId").is(jitUserId)),
                AuditEvent.class).collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(e -> e.getOp() == AuditOp.CREATE);
    }

    @Test
    void unknownOrgClaimRejected() throws Exception {
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:id", "org-unknown")
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "staff", Map.of("org-unknown", "x")))
                .claim("email", "nobody@example.test")
                .subject("zsub-x"));

        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3301);
    }

    @Test
    void missingOrgClaimRejected() throws Exception {
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "staff", Map.of("org-123", "x")))
                .claim("email", "noorg@example.test")
                .subject("zsub-noorg"));

        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3300);
    }

    @Test
    void noMappableRoleRejected() throws Exception {
        String jwt = rsaJwt(claims -> claims
                .claim("urn:zitadel:iam:org:id", "org-123")
                .claim("urn:zitadel:iam:org:project:roles", Map.of(
                        "totally-unknown-role", Map.of("org-123", "x")))
                .claim("email", "norole@example.test")
                .subject("zsub-norole"));

        web.get().uri("/contacts")
                .header("Authorization", "Bearer " + jwt)
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3302);
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
