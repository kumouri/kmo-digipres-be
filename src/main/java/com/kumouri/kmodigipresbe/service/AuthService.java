package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.LoginRequest;
import com.kumouri.kmodigipresbe.model.request.LoginResponse;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;

    public Mono<LoginResponse> login(LoginRequest req) {
        return users.findByEmailAndPortal(req.email().toLowerCase(), User.Portal.STAFF)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Invalid email or password", 1020, 401)))
                .flatMap(user -> {
                    if (user.getStatus() != User.UserStatus.ACTIVE) {
                        return Mono.error(new DigiPresBeException(
                                "Account is not active", 1021, 403));
                    }
                    if (!encoder.matches(req.password(), user.getPasswordHash())) {
                        return Mono.error(new DigiPresBeException(
                                "Invalid email or password", 1020, 401));
                    }
                    String token = tokens.mint(user);
                    // Enrich with the tenant's business name. A missing tenant, or
                    // a tenant with a null displayName, must NOT fail login — fall
                    // back to a null tenantName. switchIfEmpty is deliberately
                    // avoided (repo §9 invariant reserves it for genuine not-found
                    // errors); the Optional + defaultIfEmpty chain is null-safe.
                    return tenants.findById(user.getTenantId())
                            .map(t -> Optional.ofNullable(t.getDisplayName()))
                            .defaultIfEmpty(Optional.empty())
                            .map(name -> toResponse(user, token, name.orElse(null)));
                });
    }

    private LoginResponse toResponse(User user, String token, String tenantName) {
        return new LoginResponse(
                token,
                user.getId(),
                user.getTenantId(),
                tenantName,
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles());
    }
}
