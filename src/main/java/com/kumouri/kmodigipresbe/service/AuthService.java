package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.LoginRequest;
import com.kumouri.kmodigipresbe.model.request.LoginResponse;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.auth.LoginAttemptTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * Security fix BE-06 — a real bcrypt hash (of a random throwaway secret, cost 10),
     * computed once at class load, used ONLY to run an {@code encoder.matches} on the
     * user-not-found branch so its timing matches the found-user branch (which always runs
     * bcrypt). This neutralizes the timing oracle that otherwise distinguishes "no such
     * account" (returned before any bcrypt work) from "wrong password" (always runs bcrypt).
     * Computed (not hand-typed) so it is guaranteed a well-formed hash the encoder accepts;
     * it never authenticates anything — it is only a timing sink.
     */
    static final String DUMMY_BCRYPT_HASH =
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    private final UserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;
    private final LoginAttemptTracker loginAttempts;

    public Mono<LoginResponse> login(LoginRequest req) {
        String email = req.email().toLowerCase();
        // Security fix BE-06: per-account lockout. After too many recent failures the
        // generic 401 is returned up front, doing NO bcrypt/DB work — so an attacker
        // cannot keep guessing a single account. The same generic body as a wrong
        // password (no "locked" oracle).
        if (loginAttempts.isLocked(email)) {
            return Mono.error(new DigiPresBeException("Invalid email or password", 1020, 401));
        }
        return users.findByEmailAndPortal(email, User.Portal.STAFF)
                // Security fix BE-06: on not-found, run a dummy bcrypt compare to equalize
                // timing with the found-user path, record the failed attempt, then return
                // the SAME generic error. switchIfEmpty here is a genuine not-found branch
                // (repo §9 invariant honored).
                .switchIfEmpty(Mono.defer(() -> {
                    encoder.matches(req.password(), DUMMY_BCRYPT_HASH);
                    loginAttempts.recordFailure(email);
                    return Mono.error(new DigiPresBeException(
                            "Invalid email or password", 1020, 401));
                }))
                .flatMap(user -> {
                    // Security fix BE-06: an inactive account returns the IDENTICAL generic
                    // 401 as a wrong password — no distinct "Account is not active" oracle
                    // that would confirm the account exists. The bcrypt compare still runs
                    // first so the inactive-vs-active timing is also indistinguishable.
                    boolean passwordOk = encoder.matches(req.password(), user.getPasswordHash());
                    if (user.getStatus() != User.UserStatus.ACTIVE || !passwordOk) {
                        loginAttempts.recordFailure(email);
                        return Mono.error(new DigiPresBeException(
                                "Invalid email or password", 1020, 401));
                    }
                    loginAttempts.recordSuccess(email);
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
