package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.config.AuthModeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unauthenticated endpoint that advertises the current authentication configuration
 * to front-end clients and tooling.
 *
 * <p>Permitted without authentication in {@link com.kumouri.kmodigipresbe.config.SecurityConfig}
 * (path {@code /auth/discovery}).
 *
 * <h2>A/A2 boundary</h2>
 * Phase A ships this endpoint to expose the {@code kmosf.auth.mode} property and
 * Zitadel OIDC coordinates.  The FE uses it to redirect to the correct login flow.
 * Full Zitadel federation (tenant-claim resolution, role mapping, JIT provisioning)
 * is Phase A2.
 *
 * <h2>Error codes</h2>
 * No application errors from this endpoint — it is purely informational.
 * Error range {@code 3200-3299} is reserved for auth-mode configuration errors
 * (e.g. startup fails fast if {@code zitadel} mode is requested with a blank jwks-uri).
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthDiscoveryController {

    private final AuthModeProperties authModeProperties;

    /**
     * Returns the current authentication mode and, in {@code zitadel} mode, the
     * OIDC endpoint coordinates.
     *
     * <p>Response shape:
     * <pre>{@code
     * {
     *   "mode": "local",            // "local" | "zitadel"
     *   "issuerUri": "",            // non-blank in zitadel mode
     *   "jwksUri": "",              // non-blank in zitadel mode
     *   "loginPath": "/auth/login", // canonical local login path (kept in both modes)
     *   "authorizeUrl": "<issuer>/oauth/v2/authorize" // zitadel mode only
     * }
     * }</pre>
     *
     * <p>In {@code zitadel} mode password login is gone (410, errorCode 3303); the FE
     * uses {@code authorizeUrl} to start the Zitadel OIDC redirect. {@code loginPath}
     * is retained so a local-mode FE keeps working unchanged.
     */
    @GetMapping("/discovery")
    public Mono<Map<String, Object>> discovery() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", authModeProperties.mode());
        String issuerUri = authModeProperties.zitadel().issuerUri();
        response.put("issuerUri", issuerUri);
        response.put("jwksUri", authModeProperties.zitadel().jwksUri());
        response.put("loginPath", "/auth/login");
        if (authModeProperties.isZitadelMode()) {
            String base = issuerUri.endsWith("/")
                    ? issuerUri.substring(0, issuerUri.length() - 1)
                    : issuerUri;
            response.put("authorizeUrl", base + "/oauth/v2/authorize");
        }
        return Mono.just(response);
    }
}
