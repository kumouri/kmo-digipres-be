package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.LoginRequest;
import com.kumouri.kmodigipresbe.model.request.LoginResponse;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;

    public Mono<LoginResponse> login(LoginRequest req) {
        return users.findByEmail(req.email().toLowerCase())
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
                    return Mono.just(toResponse(user, tokens.mint(user)));
                });
    }

    private LoginResponse toResponse(User user, String token) {
        return new LoginResponse(
                token,
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles());
    }
}
