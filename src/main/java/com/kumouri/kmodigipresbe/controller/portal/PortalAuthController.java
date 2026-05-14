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
        return hostTenantResolver.resolve(exchange)
                .flatMap(tenant -> magicLinkService
                        .request(tenant, req.email(), req.linkBaseUrl())
                        .contextWrite(TenantContextHolder.write(
                                new TenantContext(tenant.getId(), null, Set.of()))));
    }

    @PostMapping("/magic-link/redeem")
    public Mono<MagicLinkRedemption> redeemMagicLink(@Valid @RequestBody MagicLinkRedeem req,
                                                     ServerWebExchange exchange) {
        return hostTenantResolver.resolve(exchange)
                .flatMap(tenant -> magicLinkService.redeem(tenant, req.token()))
                .map(user -> {
                    sessionService.issueSession(user, exchange);
                    return new MagicLinkRedemption(
                            user.getId().toString(),
                            user.getEmail(),
                            user.getDisplayName(),
                            user.getRoles());
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

    public record MagicLinkRequest(@Email @NotBlank String email, String linkBaseUrl) {}

    public record MagicLinkRedeem(@NotBlank String token) {}

    public record MagicLinkRedemption(String userId, String email, String displayName,
                                      Set<String> roles) {}

    public record PortalMe(String userId, String tenantId, String email,
                           String displayName, Set<String> roles, String portal) {}
}
