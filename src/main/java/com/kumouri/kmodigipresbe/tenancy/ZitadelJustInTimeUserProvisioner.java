package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Just-in-time {@link User} provisioning for Zitadel-federated logins (Phase A2.3).
 *
 * <p>First Zitadel login with no local {@code User} for {@code (tenant, email)} creates
 * one. Invoked from {@link ZitadelClaimTenantResolver} <em>after</em> tenant + roles are
 * known and <em>before</em> the {@link TenantContext} is returned — i.e. inside
 * {@code TenantWebFilter}'s resolve chain, on the security-context-populated thread
 * where the {@code TenantContext} is produced. A separate post-{@code TenantWebFilter}
 * filter would have no {@code TenantContext} for the stamping callback; this mirrors
 * the portal OAuth precedent which also provisions inside a tenant-context write.
 *
 * <p><strong>Provisioning.</strong> {@code User} is built with the mapped roles, the
 * email/displayName from the token, and {@code portal} derived from the roles
 * (anything containing {@code STAFF}/{@code ADMIN} → {@code STAFF}; client-only →
 * {@code CLIENT}, the A2.5 portal opt-in). The save runs with a
 * {@code TenantContext(tenantId, null, Set.of())} written into the Reactor context so
 * {@code TenantStampingCallback} stamps {@code tenantId} and {@code AuditingCallback}
 * records the CREATE (actor null on bootstrap — identical to the portal OAuth
 * precedent). {@code UuidIdAutogenCallback} assigns the id.
 *
 * <p><strong>Concurrency.</strong> Two simultaneous first-logins race the same
 * {@code (tenantId, email)}; the existing {@code tenant_email_idx} unique compound makes
 * the second insert throw {@link DuplicateKeyException}. We catch it and re-read
 * ({@code findByTenantIdAndEmail}) so both requests converge on one row — an idempotent
 * upsert via unique-index-then-reread. No new index is added.
 *
 * <p><strong>Repeat logins.</strong> When the row already exists, roles are synced to
 * the token's mapped roles (Zitadel is the source of truth in zitadel mode) — but only
 * when they actually differ, to avoid audit-event noise on every request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZitadelJustInTimeUserProvisioner {

    private final UserRepository users;

    /**
     * Resolves (creating/syncing if needed) the local {@code User} id for a federated
     * Zitadel principal. Returns the user id to fold into the {@link TenantContext}.
     */
    public Mono<UUID> provision(UUID tenantId,
                                String email,
                                String displayName,
                                Set<String> mappedRoles) {
        String normalizedEmail = email.toLowerCase();
        User.Portal portal = portalFor(mappedRoles);

        TenantContext preAuth = new TenantContext(tenantId, null, Set.of());

        return users.findByTenantIdAndEmail(tenantId, normalizedEmail)
                .flatMap(existing -> syncRolesIfChanged(existing, mappedRoles, preAuth))
                .switchIfEmpty(Mono.defer(() ->
                        createUser(tenantId, normalizedEmail, displayName, mappedRoles, portal)
                                .contextWrite(TenantContextHolder.write(preAuth))
                                // Concurrent first-login: the unique tenant_email_idx
                                // rejects the 2nd insert — re-read so both converge.
                                .onErrorResume(DuplicateKeyException.class, dup ->
                                        users.findByTenantIdAndEmail(tenantId, normalizedEmail))))
                .map(User::getId);
    }

    private Mono<User> createUser(UUID tenantId,
                                  String email,
                                  String displayName,
                                  Set<String> mappedRoles,
                                  User.Portal portal) {
        User u = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .displayName(displayName != null && !displayName.isBlank() ? displayName : email)
                .portal(portal)
                .roles(Set.copyOf(mappedRoles))
                .status(User.UserStatus.ACTIVE)
                .build();
        // tenantId is stamped by TenantStampingCallback from the Reactor context.
        return users.save(u);
    }

    private Mono<User> syncRolesIfChanged(User existing,
                                          Set<String> mappedRoles,
                                          TenantContext preAuth) {
        Set<String> desired = Set.copyOf(mappedRoles);
        Set<String> current = existing.getRoles() == null ? Set.of() : existing.getRoles();
        if (current.equals(desired)) {
            return Mono.just(existing);
        }
        existing.setRoles(desired);
        return users.save(existing)
                .contextWrite(TenantContextHolder.write(preAuth));
    }

    /**
     * Portal placement (A2.5): any STAFF/ADMIN role → staff portal; a purely
     * client-role principal → the client portal.
     */
    private User.Portal portalFor(Set<String> mappedRoles) {
        if (mappedRoles.contains("STAFF") || mappedRoles.contains("ADMIN")) {
            return User.Portal.STAFF;
        }
        return User.Portal.CLIENT;
    }
}
