package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.BootstrapTenantRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.service.TenantBootstrapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/tenants")
@RequiredArgsConstructor
public class TenantBootstrapController {

    private final TenantBootstrapService bootstrap;

    @Value("${kmosf.bootstrap.token:}")
    private String bootstrapToken;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Tenant> create(
            @RequestHeader(value = "X-Bootstrap-Token", required = false) String headerToken,
            @Valid @RequestBody BootstrapTenantRequest body) {
        if (bootstrapToken == null || bootstrapToken.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Bootstrap token is not configured on the server", 1501, 503));
        }
        if (!bootstrapToken.equals(headerToken)) {
            return Mono.error(new DigiPresBeException(
                    "Invalid bootstrap token", 1502, 401));
        }
        return bootstrap.bootstrap(body);
    }
}
