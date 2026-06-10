package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.auth.PortalInvitation;
import com.kumouri.kmodigipresbe.model.auth.UserIdentity;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.auth.PortalInvitationRepository;
import com.kumouri.kmodigipresbe.repository.auth.UserIdentityRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Provisioning chokepoint for portal sign-ins (OAuth, magic-link, passkey). Enforces
 * the tenant's {@link Tenant.ClientSignupPolicy} before creating a new {@link User}
 * and links subsequent {@link UserIdentity} rows to existing Users when the email
 * matches verified.
 * <p>All sign-in paths route through {@link #findOrProvision} so a bug in any one
 * path is bounded — the cross-tenant isolation, signup policy, and email auto-linking
 * logic only lives in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentityService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PortalInvitationRepository invitations;

    /**
     * Resolves the {@link User} for the supplied external identity, creating it if
     * permitted by the tenant's signup policy. Reactor Context must carry the
     * resolved {@link com.kumouri.kmodigipresbe.tenancy.TenantContext} so the save
     * stamping callback can populate {@code tenantId}.
     */
    public Mono<User> findOrProvision(Tenant tenant,
                                      UserIdentity.Provider provider,
                                      String providerSubject,
                                      String email,
                                      boolean emailVerified,
                                      String displayName) {
        if (providerSubject == null || providerSubject.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "External identity missing subject claim", 1220, 400));
        }
        if (email == null || email.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "External identity missing email claim", 1221, 400));
        }
        String normalizedEmail = email.toLowerCase();

        return identities.findByTenantIdAndProviderAndProviderSubject(
                        tenant.getId(), provider, providerSubject)
                .flatMap(existing -> users.findById(existing.getUserId())
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "UserIdentity references missing User", 1222, 500)))
                        .flatMap(u -> markIdentityUsed(existing).thenReturn(u)))
                .switchIfEmpty(Mono.defer(() ->
                        provisionNewIdentity(tenant, provider, providerSubject,
                                normalizedEmail, emailVerified, displayName)));
    }

    private Mono<User> provisionNewIdentity(Tenant tenant,
                                            UserIdentity.Provider provider,
                                            String providerSubject,
                                            String email,
                                            boolean emailVerified,
                                            String displayName) {
        return assertSignupAllowed(tenant, email, emailVerified)
                .then(users.findByTenantIdAndEmail(tenant.getId(), email))
                .switchIfEmpty(Mono.defer(() -> createPortalUser(email, displayName)))
                .flatMap(user -> createIdentity(user, provider, providerSubject, email,
                        emailVerified, displayName)
                        .thenReturn(user))
                .flatMap(user -> redeemMatchingInvitations(tenant, user, email)
                        .thenReturn(user));
    }

    /**
     * Throws if the tenant's signup policy does not permit a brand-new sign-in for
     * this email. Existing {@link User}s (matched by tenant+email) bypass this gate
     * because they were already provisioned via some other path.
     */
    private Mono<Void> assertSignupAllowed(Tenant tenant, String email, boolean emailVerified) {
        if (!emailVerified) {
            return Mono.error(new DigiPresBeException(
                    "Provider did not return a verified email; sign-in blocked",
                    1223, 403));
        }
        // Existing User on this tenant: linking another provider is always allowed
        // because they're already a member here.
        return users.findByTenantIdAndEmail(tenant.getId(), email)
                .hasElement()
                .flatMap(exists -> {
                    if (exists) return Mono.empty();
                    return switch (effectivePolicy(tenant)) {
                        case INVITE_ONLY -> assertPendingInvitation(tenant, email);
                        case OPEN_DOMAIN -> assertEmailDomainAllowed(tenant, email);
                        case OPEN -> Mono.empty();
                    };
                });
    }

    private Tenant.ClientSignupPolicy effectivePolicy(Tenant tenant) {
        return tenant.getClientSignupPolicy() == null
                ? Tenant.ClientSignupPolicy.INVITE_ONLY
                : tenant.getClientSignupPolicy();
    }

    private Mono<Void> assertPendingInvitation(Tenant tenant, String email) {
        return invitations.findAllByTenantIdAndEmailAndStatus(
                        tenant.getId(), email, PortalInvitation.Status.PENDING)
                .filter(inv -> inv.getExpiresAt() == null
                        || inv.getExpiresAt().isAfter(Instant.now()))
                .next()
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "Tenant requires an invitation for new portal sign-ins. "
                                + "Contact your account owner.",
                        1224, 403)))
                .then();
    }

    private Mono<Void> assertEmailDomainAllowed(Tenant tenant, String email) {
        Set<String> allowed = tenant.getAllowedSignupDomains();
        if (allowed == null || allowed.isEmpty()) {
            return Mono.error(new DigiPresBeException(
                    "Tenant policy is OPEN_DOMAIN but no allowed domains are configured.",
                    1225, 500));
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return Mono.error(new DigiPresBeException(
                    "Malformed email; cannot extract domain.", 1226, 400));
        }
        String domain = email.substring(at + 1).toLowerCase();
        if (!allowed.contains(domain)) {
            return Mono.error(new DigiPresBeException(
                    "Email domain " + domain + " is not allowed for tenant signup.",
                    1227, 403));
        }
        return Mono.empty();
    }

    private Mono<User> createPortalUser(String email, String displayName) {
        User u = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .displayName(displayName != null ? displayName : email)
                .portal(User.Portal.CLIENT)
                .roles(Set.of("CLIENT"))
                .status(User.UserStatus.ACTIVE)
                .build();
        // tenantId comes from TenantStampingCallback via Reactor Context.
        return users.save(u);
    }

    private Mono<UserIdentity> createIdentity(User user,
                                              UserIdentity.Provider provider,
                                              String providerSubject,
                                              String email,
                                              boolean emailVerified,
                                              String displayName) {
        UserIdentity id = UserIdentity.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .provider(provider)
                .providerSubject(providerSubject)
                .email(email)
                .emailVerified(emailVerified)
                .displayName(displayName)
                .linkedAt(Instant.now())
                .lastUsedAt(Instant.now())
                .build();
        return identities.save(id);
    }

    private Mono<Void> markIdentityUsed(UserIdentity identity) {
        identity.setLastUsedAt(Instant.now());
        return identities.save(identity).then();
    }

    /**
     * Mark any matching PENDING invitation as redeemed by this user. Inviting Ceryce
     * once for a tenant should not cause repeated redemption events on repeat logins.
     */
    private Mono<Void> redeemMatchingInvitations(Tenant tenant, User user, String email) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                invitations.findAllByTenantIdAndEmailAndStatus(
                        tenant.getId(), email, PortalInvitation.Status.PENDING))
                .flatMap(inv -> {
                    inv.setStatus(PortalInvitation.Status.REDEEMED);
                    inv.setRedeemedAt(Instant.now());
                    inv.setRedeemedAsUserId(user.getId());
                    addRoles(user, inv.getRoles());
                    return invitations.save(inv);
                })
                .then(Mono.defer(() -> users.save(user)))
                .then();
    }

    /**
     * Merge roles granted by a redeemed invitation onto the user.
     *
     * <p><strong>Security fix BE-01 (defense-in-depth):</strong> an invitation may ONLY
     * grant the {@code CLIENT} role. Even though {@code PortalInvitationController} now
     * rejects non-CLIENT roles at creation, this is the second wall — a self-service
     * portal sign-in redeeming an invitation must NEVER be able to escalate the user to
     * {@code STAFF}/{@code ADMIN}, regardless of what roles a tampered or legacy
     * invitation row carries. Any non-CLIENT role on the invitation is silently dropped.
     */
    private static void addRoles(User user, Set<String> add) {
        if (add == null || add.isEmpty()) return;
        Set<String> safeAdditions = add.stream()
                .filter("CLIENT"::equals)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (safeAdditions.isEmpty()) return;
        Set<String> merged = new LinkedHashSet<>(user.getRoles() == null
                ? Set.of() : user.getRoles());
        merged.addAll(safeAdditions);
        user.setRoles(merged);
    }

    /**
     * Lookup existing identities for a user — used by the portal settings screen to
     * show "this account is linked to Google, Microsoft" etc.
     */
    public Flux<UserIdentity> listForUser(UUID userId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> identities.findAllByTenantIdAndUserId(ctx.tenantId(), userId));
    }
}
