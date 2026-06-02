package com.kumouri.kmodigipresbe.service.contractor;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.TeamMemberRequest;
import com.kumouri.kmodigipresbe.model.response.TeamMemberView;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Staff / contractor directory (Phase J) — the first user-management surface in the system
 * (users are otherwise created only by {@code TenantBootstrapService} and the portal
 * invitation flow). ADMIN-gated at the controller.
 *
 * <p>Returns {@link TeamMemberView} projections — the raw {@code User} (with its
 * {@code passwordHash}) never crosses the wire.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final Set<String> ALLOWED_ROLES = Set.of("STAFF", "CONTRACTOR", "ADMIN");

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public Flux<TeamMemberView> list() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> users.findAllByTenantIdAndPortal(ctx.tenantId(), User.Portal.STAFF))
                .map(TeamMemberView::from);
    }

    public Mono<TeamMemberView> create(TeamMemberRequest req) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (req.email() == null || req.email().isBlank()) {
                return Mono.error(new DigiPresBeException("Email is required", 4102, 400));
            }
            if (req.displayName() == null || req.displayName().isBlank()) {
                return Mono.error(new DigiPresBeException("Display name is required", 4103, 400));
            }
            boolean hasPassword = req.password() != null && !req.password().isBlank();
            User.UserStatus status = req.status() != null
                    ? parseStatus(req.status())
                    : (hasPassword ? User.UserStatus.ACTIVE : User.UserStatus.INVITED);
            User u = User.builder()
                    .id(UUID.randomUUID())
                    .tenantId(ctx.tenantId())
                    .email(req.email().toLowerCase())
                    .passwordHash(hasPassword ? encoder.encode(req.password()) : null)
                    .displayName(req.displayName())
                    .roles(sanitizeRoles(req.roles()))
                    .status(status)
                    .portal(User.Portal.STAFF)
                    .defaultBillRate(req.defaultBillRate())
                    .defaultCostRate(req.defaultCostRate())
                    .build();
            return users.save(u)
                    .onErrorMap(DuplicateKeyException.class, ex -> new DigiPresBeException(
                            "A team member with this email already exists", 4104, 409))
                    .map(TeamMemberView::from);
        });
    }

    public Mono<TeamMemberView> update(UUID id, TeamMemberRequest req) {
        return loadStaff(id).flatMap(existing -> {
            if (req.displayName() != null && !req.displayName().isBlank()) {
                existing.setDisplayName(req.displayName());
            }
            if (req.roles() != null) {
                existing.setRoles(sanitizeRoles(req.roles()));
            }
            if (req.status() != null) {
                existing.setStatus(parseStatus(req.status()));
            }
            if (req.defaultBillRate() != null) {
                existing.setDefaultBillRate(req.defaultBillRate());
            }
            if (req.defaultCostRate() != null) {
                existing.setDefaultCostRate(req.defaultCostRate());
            }
            if (req.password() != null && !req.password().isBlank()) {
                existing.setPasswordHash(encoder.encode(req.password()));
            }
            return users.save(existing).map(TeamMemberView::from);
        });
    }

    public Mono<TeamMemberView> disable(UUID id) {
        return loadStaff(id).flatMap(u -> {
            u.setStatus(User.UserStatus.DISABLED);
            return users.save(u).map(TeamMemberView::from);
        });
    }

    private Mono<User> loadStaff(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> users.findById(id)
                        .filter(u -> ctx.tenantId().equals(u.getTenantId())
                                && u.getPortal() == User.Portal.STAFF))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Team member not found", 4140, 404)));
    }

    private Set<String> sanitizeRoles(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of("STAFF");
        }
        Set<String> roles = new HashSet<>(requested);
        roles.retainAll(ALLOWED_ROLES);
        if (roles.isEmpty()) {
            roles.add("STAFF");
        }
        // CONTRACTOR / ADMIN ride the staff security chain — keep STAFF present.
        if (roles.contains("CONTRACTOR") || roles.contains("ADMIN")) {
            roles.add("STAFF");
        }
        return roles;
    }

    private User.UserStatus parseStatus(String status) {
        try {
            return User.UserStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("Unknown status: " + status, 4105, 400);
        }
    }
}
