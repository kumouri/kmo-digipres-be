package com.kumouri.kmodigipresbe.controller.admin;

import com.kumouri.kmodigipresbe.config.PortalProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.auth.PortalInvitation;
import com.kumouri.kmodigipresbe.repository.auth.PortalInvitationRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * Staff-side CRUD for portal invitations. Lives on the staff security chain (not
 * {@code /portal/**}) — the inviter is an authenticated KMOSF staff user managing
 * their tenant's clients.
 */
@RestController
@RequestMapping("/admin/portal-invitations")
@RequiredArgsConstructor
public class PortalInvitationController {

    private final PortalInvitationRepository invitations;
    private final PortalProperties portalProperties;

    @GetMapping
    public Flux<InvitationView> list() {
        return invitations.findAll().map(this::toView);
    }

    @PostMapping
    public Mono<IssuedInvitation> create(@Valid @RequestBody NewInvitation req) {
        return TenantContextHolder.required().flatMap(ctx -> {
            // Security fix BE-01: a portal invitation may ONLY grant the CLIENT role.
            // The roles set is client-settable and is merged onto the user at
            // sign-in (UserIdentityService.redeemMatchingInvitations), so accepting
            // ADMIN/STAFF here would be a self-service privilege escalation to tenant
            // admin. Reject any non-CLIENT role outright (defense-in-depth alongside
            // the same guard in UserIdentityService.addRoles).
            if (hasNonClientRole(req.roles())) {
                return Mono.error(new DigiPresBeException(
                        "Portal invitations may only grant the CLIENT role", 1820, 400));
            }
            String raw = generateRawToken();
            PortalInvitation inv = PortalInvitation.builder()
                    .id(UUID.randomUUID())
                    .email(req.email().toLowerCase())
                    .roles(Set.of("CLIENT"))
                    .tokenHash(sha256Hex(raw))
                    .expiresAt(Instant.now().plus(Duration.ofHours(
                            portalProperties.invitationTtlHours())))
                    .status(PortalInvitation.Status.PENDING)
                    .invitedByUserId(ctx.userId())
                    .build();
            return invitations.save(inv).map(saved -> new IssuedInvitation(
                    saved.getId().toString(),
                    saved.getEmail(),
                    raw,
                    saved.getExpiresAt()));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<Void> revoke(@PathVariable UUID id) {
        return invitations.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Invitation not found", 1260, 404)))
                .flatMap(inv -> {
                    inv.setStatus(PortalInvitation.Status.REVOKED);
                    return invitations.save(inv);
                })
                .then();
    }

    private InvitationView toView(PortalInvitation inv) {
        return new InvitationView(
                inv.getId().toString(),
                inv.getEmail(),
                inv.getRoles(),
                inv.getStatus().name(),
                inv.getExpiresAt(),
                inv.getRedeemedAt());
    }

    /**
     * True if {@code roles} contains any role other than {@code CLIENT} (security fix
     * BE-01). A {@code null}/empty set defaults to CLIENT-only at creation, so it is not
     * a violation.
     */
    private static boolean hasNonClientRole(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        return roles.stream().anyMatch(role -> !"CLIENT".equals(role));
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new DigiPresBeException(ex, 1261, 500);
        }
    }

    public record NewInvitation(@Email @NotBlank String email, Set<String> roles) {}

    public record IssuedInvitation(String id, String email, String token,
                                   Instant expiresAt) {}

    public record InvitationView(String id, String email, Set<String> roles,
                                 String status, Instant expiresAt, Instant redeemedAt) {}
}
