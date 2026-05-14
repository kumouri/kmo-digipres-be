package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.LoginRequest;
import com.kumouri.kmodigipresbe.model.request.LoginResponse;
import com.kumouri.kmodigipresbe.model.user.User;
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

    @PostMapping("/login")
    public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
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
                    return users.findById(ctx.userId());
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
