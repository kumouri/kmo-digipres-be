package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.UUID;

/**
 * Central default-deny authorization filter ({@link StaffAuthorizationWebFilter}) — the
 * security-fix BE-02 + BE-04 acceptance suite. Exercises the real HTTP stack (every token
 * is a genuinely-minted HS256 JWT) so the filter runs in the actual staff security chain
 * with {@code TenantWebFilter} ahead of it.
 *
 * <p>Token matrix:
 * <ul>
 *   <li><b>portal CLIENT</b> ({@code portal=CLIENT}, {@code roles=[CLIENT]}) — must be 403
 *       on every staff endpoint (BE-04 boundary collapse).</li>
 *   <li><b>STAFF-only</b> ({@code portal=STAFF}, {@code roles=[STAFF]}) — 200 on staff reads,
 *       403 on {@code /admin/**} and {@code /integrations/connections}.</li>
 *   <li><b>ADMIN</b> ({@code portal=STAFF}, {@code roles=[STAFF,ADMIN]}) — 200 on admin reads.</li>
 *   <li><b>no-roles</b> ({@code portal=STAFF}, {@code roles=[]}) — 403 (STAFF baseline).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class StaffAuthorizationFilterIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String clientToken;
    private String staffToken;
    private String adminToken;
    private String noRolesToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("authz-it-" + tenantId)
                .displayName("Authz IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        User client = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("client@authz.test").roles(Set.of("CLIENT"))
                .portal(User.Portal.CLIENT).status(User.UserStatus.ACTIVE)
                .contactId(UUID.randomUUID()).build()).block();
        clientToken = jwt.mint(client);

        User staff = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@authz.test").roles(Set.of("STAFF"))
                .portal(User.Portal.STAFF).status(User.UserStatus.ACTIVE).build()).block();
        staffToken = jwt.mint(staff);

        User admin = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@authz.test").roles(Set.of("STAFF", "ADMIN"))
                .portal(User.Portal.STAFF).status(User.UserStatus.ACTIVE).build()).block();
        adminToken = jwt.mint(admin);

        User noRoles = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("noroles@authz.test").roles(Set.of())
                .portal(User.Portal.STAFF).status(User.UserStatus.ACTIVE).build()).block();
        noRolesToken = jwt.mint(noRoles);
    }

    private WebTestClient.ResponseSpec get(String uri, String token) {
        return web.get().uri(uri)
                .header("Authorization", "Bearer " + token)
                .exchange();
    }

    // ─── portal CLIENT token is rejected on every staff endpoint (BE-04) ─────────

    @Test
    void clientToken_forbiddenOnContacts() {
        get("/contacts", clientToken).expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1802);
    }

    @Test
    void clientToken_forbiddenOnAdmin() {
        get("/admin/modules", clientToken).expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1802);
    }

    @Test
    void clientToken_forbiddenOnIntegrationConnections() {
        get("/integrations/connections", clientToken).expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1802);
    }

    @Test
    void clientToken_forbiddenOnSync() {
        get("/sync/contacts?since=1970-01-01T00:00:00Z", clientToken)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1802);
    }

    // ─── STAFF-only token: 200 on staff reads, 403 on admin / integration ────────

    @Test
    void staffToken_okOnContacts() {
        get("/contacts", staffToken).expectStatus().isOk();
    }

    @Test
    void staffToken_forbiddenOnAdmin() {
        get("/admin/modules", staffToken).expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1804);
    }

    @Test
    void staffToken_forbiddenOnIntegrationConnections() {
        get("/integrations/connections", staffToken).expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1804);
    }

    // ─── ADMIN token: 200 on admin reads ─────────────────────────────────────────

    @Test
    void adminToken_okOnAdmin() {
        get("/admin/modules", adminToken).expectStatus().isOk();
    }

    @Test
    void adminToken_okOnIntegrationConnections() {
        get("/integrations/connections", adminToken).expectStatus().isOk();
    }

    @Test
    void adminToken_okOnContacts() {
        get("/contacts", adminToken).expectStatus().isOk();
    }

    // ─── no-roles token: 403 baseline (STAFF required) ───────────────────────────

    @Test
    void noRolesToken_forbiddenOnContacts() {
        get("/contacts", noRolesToken).expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1803);
    }

    @Test
    void noRolesToken_forbiddenOnAdmin() {
        get("/admin/modules", noRolesToken).expectStatus().isForbidden();
    }

    // ─── unauthenticated still 401 (filter does not change the auth wall) ─────────

    @Test
    void unauthenticated_unauthorizedOnContacts() {
        web.get().uri("/contacts").exchange().expectStatus().isUnauthorized();
    }

    // ─── permitAll surface is unaffected by the filter ───────────────────────────

    @Test
    void openApiSpec_reachableUnauthenticated() {
        web.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
    }

    @Test
    void authHealth_reachableUnauthenticated() {
        web.get().uri("/auth/health").exchange().expectStatus().isOk();
    }
}
