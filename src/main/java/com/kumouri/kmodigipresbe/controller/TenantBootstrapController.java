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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
        // Security fix BE-18: constant-time compare to avoid a remote timing side-channel
        // on the high-entropy bootstrap token. MessageDigest.isEqual short-circuits only on
        // length, not content. A null/blank header fails closed (the configured token is
        // already asserted non-blank above; a null headerToken → empty byte[] → mismatch).
        byte[] configured = bootstrapToken.getBytes(StandardCharsets.UTF_8);
        byte[] presented = headerToken == null
                ? new byte[0]
                : headerToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(configured, presented)) {
            return Mono.error(new DigiPresBeException(
                    "Invalid bootstrap token", 1502, 401));
        }
        return bootstrap.bootstrap(body);
    }
}
