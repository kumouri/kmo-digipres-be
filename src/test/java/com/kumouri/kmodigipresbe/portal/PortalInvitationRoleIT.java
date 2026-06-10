package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.auth.PortalInvitation;
import com.kumouri.kmodigipresbe.model.auth.UserIdentity;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.auth.PortalInvitationRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.service.portal.UserIdentityService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-01 — portal-invitation privilege escalation is blocked at both walls:
 * <ol>
 *   <li><b>Creation</b>: {@code POST /admin/portal-invitations} with any role other than
 *       {@code CLIENT} is rejected 400 (errorCode 1820). The route is also ADMIN-gated by
 *       {@link com.kumouri.kmodigipresbe.tenancy.StaffAuthorizationWebFilter}.</li>
 *   <li><b>Redemption (defense-in-depth)</b>: even a tampered/legacy PENDING invitation
 *       carrying {@code [ADMIN,STAFF]} never merges a non-CLIENT role onto the provisioned
 *       user — {@code UserIdentityService.addRoles} drops everything but CLIENT.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalInvitationRoleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PortalInvitationRepository invitations;
    @Autowired UserIdentityService userIdentityService;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private Tenant tenant;
    private String adminToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), PortalInvitation.class).block();
        mongo.remove(new Query(), UserIdentity.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenant = tenants.save(Tenant.builder()
                .id(UUID.randomUUID()).slug("inv-role-" + UUID.randomUUID())
                .displayName("Invitation Role IT").status(Tenant.TenantStatus.ACTIVE)
                .clientSignupPolicy(Tenant.ClientSignupPolicy.INVITE_ONLY)
                .build()).block();

        User admin = users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenant.getId())
                .email("admin@inv.test").roles(Set.of("STAFF", "ADMIN"))
                .portal(User.Portal.STAFF).status(User.UserStatus.ACTIVE).build()).block();
        adminToken = jwt.mint(admin);
    }

    // ─── Wall 1: creation rejects a non-CLIENT role ──────────────────────────────

    @Test
    void createInvitation_withAdminRole_rejected400() {
        web.post().uri("/admin/portal-invitations")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("email", "attacker@evil.com",
                        "roles", Set.of("ADMIN", "STAFF")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1820);
    }

    @Test
    void createInvitation_withClientRole_allowed() {
        web.post().uri("/admin/portal-invitations")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("email", "client@ok.com", "roles", Set.of("CLIENT")))
                .exchange()
                // create() has no @ResponseStatus → 200 OK (not 201)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo("client@ok.com");
    }

    @Test
    void createInvitation_withNoRoles_defaultsToClient() {
        web.post().uri("/admin/portal-invitations")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("email", "defaulted@ok.com"))
                .exchange()
                .expectStatus().isOk();

        // PortalInvitation is TenantScoped — the repository read needs a TenantContext.
        PortalInvitation saved = invitations.findAll()
                .filter(i -> "defaulted@ok.com".equals(i.getEmail()))
                .next()
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
        assertThat(saved).isNotNull();
        assertThat(saved.getRoles()).containsExactly("CLIENT");
    }

    // ─── Wall 2: redemption of a TAMPERED invitation never grants non-CLIENT ──────

    @Test
    void redeemTamperedInvitation_neverGrantsNonClientRole() {
        // Simulate a tampered/legacy PENDING invitation that somehow carries ADMIN+STAFF
        // (e.g. written before the controller guard existed). Persist it directly.
        String email = "escalate@evil.com";
        invitations.save(PortalInvitation.builder()
                        .id(UUID.randomUUID())
                        .email(email)
                        .roles(Set.of("ADMIN", "STAFF", "CLIENT"))
                        .tokenHash("tampered-" + UUID.randomUUID())
                        .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                        .status(PortalInvitation.Status.PENDING)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        // Drive the same provisioning chokepoint the magic-link redeem path uses.
        User provisioned = userIdentityService.findOrProvision(
                        tenant, UserIdentity.Provider.MAGIC_LINK, email, email, true, email)
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        assertThat(provisioned).isNotNull();
        // The escalation must be neutralized: the user keeps CLIENT only — never ADMIN/STAFF.
        assertThat(provisioned.getRoles()).containsExactly("CLIENT");
        assertThat(provisioned.getRoles()).doesNotContain("ADMIN", "STAFF");

        // And the persisted user (after invitation redemption save) is likewise CLIENT-only.
        User reloaded = users.findById(provisioned.getId())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getRoles()).containsExactly("CLIENT");
    }

    private TenantContext ctx() {
        return new TenantContext(tenant.getId(), null, Set.of());
    }
}
