package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.auth.PortalInvitation;
import com.kumouri.kmodigipresbe.model.auth.UserIdentity;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.auth.PortalInvitationRepository;
import com.kumouri.kmodigipresbe.repository.auth.UserIdentityRepository;
import com.kumouri.kmodigipresbe.service.portal.UserIdentityService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the {@link UserIdentityService} provisioning gate for each
 * {@link com.kumouri.kmodigipresbe.model.tenant.Tenant.ClientSignupPolicy} variant.
 * The provisioning chokepoint is the only place this policy is enforced; if it
 * regresses, OAuth and magic-link both leak self-provisioning.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserIdentityProvisioningPolicyIT {

    @Autowired UserIdentityService userIdentityService;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired UserIdentityRepository identities;
    @Autowired PortalInvitationRepository invitations;

    private Tenant inviteOnly;
    private Tenant openDomain;
    private Tenant openTenant;

    @BeforeEach
    void seed() {
        inviteOnly = save(buildTenant("invite-only-" + UUID.randomUUID(),
                Tenant.ClientSignupPolicy.INVITE_ONLY, Set.of()));
        openDomain = save(buildTenant("open-domain-" + UUID.randomUUID(),
                Tenant.ClientSignupPolicy.OPEN_DOMAIN, Set.of("allowed.example")));
        openTenant = save(buildTenant("open-" + UUID.randomUUID(),
                Tenant.ClientSignupPolicy.OPEN, Set.of()));
    }

    @Test
    void inviteOnlyRejectsUninvitedSignup() {
        StepVerifier.create(
                        userIdentityService.findOrProvision(
                                        inviteOnly, UserIdentity.Provider.GOOGLE,
                                        "google-sub-1", "stranger@example.com",
                                        true, "Stranger")
                                .contextWrite(TenantContextHolder.write(ctx(inviteOnly))))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1224)
                .verify();
    }

    @Test
    void inviteOnlyAllowsInvitedSignup() {
        PortalInvitation inv = PortalInvitation.builder()
                .id(UUID.randomUUID())
                .email("guest@example.com")
                .tokenHash("hash-" + UUID.randomUUID())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .status(PortalInvitation.Status.PENDING)
                .build();
        invitations.save(inv)
                .contextWrite(TenantContextHolder.write(ctx(inviteOnly)))
                .block();

        var user = userIdentityService.findOrProvision(
                        inviteOnly, UserIdentity.Provider.GOOGLE,
                        "google-sub-2", "guest@example.com",
                        true, "Guest")
                .contextWrite(TenantContextHolder.write(ctx(inviteOnly)))
                .block();

        assertThat(user).isNotNull();
        assertThat(user.getPortal()).isEqualTo(
                com.kumouri.kmodigipresbe.model.user.User.Portal.CLIENT);
        assertThat(user.getEmail()).isEqualTo("guest@example.com");
    }

    @Test
    void openDomainAllowsMatchingDomain() {
        var user = userIdentityService.findOrProvision(
                        openDomain, UserIdentity.Provider.GOOGLE,
                        "google-sub-3", "alice@allowed.example",
                        true, "Alice")
                .contextWrite(TenantContextHolder.write(ctx(openDomain)))
                .block();
        assertThat(user).isNotNull();
        assertThat(user.getEmail()).isEqualTo("alice@allowed.example");
    }

    @Test
    void openDomainRejectsForeignDomain() {
        StepVerifier.create(
                        userIdentityService.findOrProvision(
                                        openDomain, UserIdentity.Provider.GOOGLE,
                                        "google-sub-4", "bob@other.example",
                                        true, "Bob")
                                .contextWrite(TenantContextHolder.write(ctx(openDomain))))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1227)
                .verify();
    }

    @Test
    void openPolicyAllowsAnyEmail() {
        var user = userIdentityService.findOrProvision(
                        openTenant, UserIdentity.Provider.GOOGLE,
                        "google-sub-5", "anyone@anywhere.example",
                        true, "Anyone")
                .contextWrite(TenantContextHolder.write(ctx(openTenant)))
                .block();
        assertThat(user).isNotNull();
    }

    @Test
    void unverifiedEmailIsRejectedRegardlessOfPolicy() {
        StepVerifier.create(
                        userIdentityService.findOrProvision(
                                        openTenant, UserIdentity.Provider.GOOGLE,
                                        "google-sub-6", "unverified@example.com",
                                        false, "X")
                                .contextWrite(TenantContextHolder.write(ctx(openTenant))))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1223)
                .verify();
    }

    @Test
    void secondIdentityForSameEmailLinksToExistingUser() {
        // First sign-in: Google
        var first = userIdentityService.findOrProvision(
                        openTenant, UserIdentity.Provider.GOOGLE,
                        "google-sub-7", "linked@example.com",
                        true, "Linked")
                .contextWrite(TenantContextHolder.write(ctx(openTenant)))
                .block();
        // Second sign-in: Microsoft, same verified email
        var second = userIdentityService.findOrProvision(
                        openTenant, UserIdentity.Provider.MICROSOFT,
                        "ms-sub-7", "linked@example.com",
                        true, "Linked")
                .contextWrite(TenantContextHolder.write(ctx(openTenant)))
                .block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());
        Long identityCount = identities
                .findAllByTenantIdAndUserId(openTenant.getId(), first.getId())
                .count().block();
        assertThat(identityCount).isEqualTo(2L);
    }

    private TenantContext ctx(Tenant t) {
        return new TenantContext(t.getId(), null, Set.of());
    }

    private Tenant buildTenant(String slug, Tenant.ClientSignupPolicy policy,
                               Set<String> allowedDomains) {
        return Tenant.builder()
                .id(UUID.randomUUID())
                .slug(slug)
                .displayName(slug)
                .status(Tenant.TenantStatus.ACTIVE)
                .clientSignupPolicy(policy)
                .allowedSignupDomains(allowedDomains)
                .build();
    }

    private Tenant save(Tenant t) {
        return tenants.save(t).block();
    }
}
