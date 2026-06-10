package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.portal.MagicLinkService;
import com.kumouri.kmodigipresbe.service.portal.PortalSessionService;
import com.kumouri.kmodigipresbe.tenancy.HostTenantResolver;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Cross-mechanism portal auth surface — magic-link issuance + redemption, current-user
 * lookup, and logout. OAuth login is handled by Spring Security's {@code oauth2Login()}
 * configurer on the portal chain; passkey login lives on a separate controller.
 */
@RestController
@RequestMapping("/portal/auth")
@RequiredArgsConstructor
public class PortalAuthController {

    private final MagicLinkService magicLinkService;
    private final PortalSessionService sessionService;
    private final HostTenantResolver hostTenantResolver;
    private final UserRepository users;

    @PostMapping("/magic-link")
    public Mono<Void> requestMagicLink(@Valid @RequestBody MagicLinkRequest req,
                                       ServerWebExchange exchange) {
        // Security fix BE-05: the emailed link base is NEVER taken from the request body.
        // A client-controlled linkBaseUrl let an attacker have the real, branded sign-in
        // email carry a LIVE one-time token to an attacker host (token exfiltration →
        // account takeover). The link base is now derived server-side
        // (MagicLinkService → portalProperties.successRedirect()). redirectTo is the
        // already-hardened deep-link target (persisted at request-time, echoed at redeem).
        return hostTenantResolver.resolve(exchange)
                .flatMap(tenant -> magicLinkService
                        .request(tenant, req.email(), req.redirectTo())
                        .contextWrite(TenantContextHolder.write(
                                new TenantContext(tenant.getId(), null, Set.of()))));
    }

    @PostMapping("/magic-link/redeem")
    public Mono<MagicLinkRedemption> redeemMagicLink(@Valid @RequestBody MagicLinkRedeem req,
                                                     ServerWebExchange exchange) {
        return hostTenantResolver.resolve(exchange)
                .flatMap(tenant -> magicLinkService.redeem(tenant, req.token()))
                .map(result -> {
                    sessionService.issueSession(result.user(), exchange);
                    // Open-redirect mitigation (§9 #5): redirectTo is ALWAYS result.redirectTo()
                    // which is token.getRedirectTo() — the value persisted at request-time.
                    // It is NEVER read from the current redeem request body.
                    return new MagicLinkRedemption(
                            result.user().getId().toString(),
                            result.user().getEmail(),
                            result.user().getDisplayName(),
                            result.user().getRoles(),
                            result.redirectTo());
                });
    }

    @GetMapping("/me")
    public Mono<PortalMe> me() {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (ctx.userId() == null) {
                return Mono.error(new DigiPresBeException(
                        "No user id in token", 1250, 401));
            }
            return users.findById(ctx.userId())
                    .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                            "User not found", 1251, 404)))
                    .map(u -> new PortalMe(
                            u.getId().toString(),
                            u.getTenantId().toString(),
                            u.getEmail(),
                            u.getDisplayName(),
                            u.getRoles(),
                            u.getPortal() == null ? null : u.getPortal().name()));
        });
    }

    @PostMapping("/logout")
    public Mono<Void> logout(ServerWebExchange exchange) {
        sessionService.clearSession(exchange);
        return Mono.empty();
    }

    /**
     * Request body for {@code POST /portal/auth/magic-link}.
     *
     * <p>Security fix BE-05: {@code linkBaseUrl} was REMOVED — the emailed link base is
     * derived server-side from {@code portalProperties.successRedirect()}, never from the
     * request, so a sign-in email can no longer be steered to carry a live token to an
     * attacker host. {@code redirectTo} (G.5 — additive, nullable) is an optional
     * deep-link target persisted on the token and echoed back in the redeem response so
     * the portal FE can route post-login; it is open-redirect-safe (always the persisted
     * value, never read from the redeem request). When absent (null) behaviour is
     * byte-identical to pre-G.5.
     */
    public record MagicLinkRequest(@Email @NotBlank String email, String redirectTo) {}

    public record MagicLinkRedeem(@NotBlank String token) {}

    /**
     * Response for {@code POST /portal/auth/magic-link/redeem}.
     *
     * <p>{@code redirectTo} (G.5 — additive, nullable) echoes the deep-link target that
     * was supplied and persisted at request-time. It is ALWAYS sourced from the persisted
     * token field — NEVER from the redeem request body (open-redirect mitigation: §9 #5).
     * Null means no deep-link was supplied when the token was issued.
     */
    public record MagicLinkRedemption(String userId, String email, String displayName,
                                      Set<String> roles, String redirectTo) {}

    public record PortalMe(String userId, String tenantId, String email,
                           String displayName, Set<String> roles, String portal) {}
}
