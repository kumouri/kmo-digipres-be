package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.config.AuthModeProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.LoginRequest;
import com.kumouri.kmodigipresbe.model.request.LoginResponse;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.AuthService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final AuthModeProperties authModeProperties;

    /**
     * Staff password login. In {@code local} auth-mode this mints an HS256 token as
     * before. In {@code zitadel} auth-mode password login is disabled — the deployment
     * federates to Zitadel — and this responds {@code 410 Gone} with errorCode 3303
     * and an RFC7807 body pointing the FE at {@code /auth/discovery} (which carries the
     * Zitadel {@code authorizeUrl}). The endpoint deliberately still responds (an old
     * build's client gets a clear 410, not a 404). {@code AuthService}/
     * {@code JwtTokenService} are untouched.
     */
    @PostMapping("/login")
    public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        if (authModeProperties.isZitadelMode()) {
            return Mono.error(new DigiPresBeException(
                    "Password login is disabled; this deployment federates to Zitadel. "
                            + "See /api/v1/auth/discovery.",
                    3303, 410));
        }
        return authService.login(req);
    }

    @GetMapping("/me")
    public Mono<User> me() {
        return TenantContextHolder.required()
                .flatMap(ctx -> {
                    if (ctx.userId() == null) {
                        return Mono.error(new DigiPresBeException(
                                "No user id in token", 1022, 401));
                    }
                    // Enrich /auth/me with the tenant's business name (non-persisted
                    // projection). Empty/absent tenant → leave tenantName null and
                    // still return the user. No switchIfEmpty (repo §9 invariant).
                    return users.findById(ctx.userId())
                            .flatMap(user -> tenants.findById(user.getTenantId())
                                    .map(t -> {
                                        user.setTenantName(t.getDisplayName());
                                        return user;
                                    })
                                    .defaultIfEmpty(user));
                });
    }

    @PostMapping("/logout")
    public Mono<Void> logout() {
        // Stateless JWTs — client just discards the token. Endpoint exists for symmetry.
        return Mono.empty();
    }

    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("ok");
    }
}
