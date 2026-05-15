package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.square.SquareOAuthService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin endpoints for the Square OAuth2 consent flow.
 *
 * <ul>
 *   <li>{@code GET /integrations/square/oauth/start} — admin-only; returns
 *       the Square consent URL the admin should redirect to.</li>
 *   <li>{@code GET /public/integrations/square/oauth/callback} — anonymous;
 *       Square redirects the browser here with {@code code} and {@code state}.</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(prefix = "kmosf.integrations.square", name = "enabled")
@RequiredArgsConstructor
public class SquareOAuthController {

    private final SquareOAuthService oauth;

    @RequestMapping(value = "/integrations/square/oauth/start", method = {
            RequestMethod.GET, RequestMethod.POST
    })
    public Mono<Map<String, String>> start() {
        return RoleGuard.requireRole("ADMIN")
                .then(TenantContextHolder.required())
                .flatMap(ctx -> oauth.buildAuthorizationUrl(ctx.tenantId()))
                .map(url -> Map.of("authorizationUrl", url));
    }

    @GetMapping("/public/integrations/square/oauth/callback")
    @ResponseStatus(HttpStatus.OK)
    public Mono<IntegrationConnection> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null && !error.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Square consent denied or errored: " + error
                            + (errorDescription != null ? " — " + errorDescription : ""),
                    3001, 400));
        }
        return oauth.exchangeAuthCode(code, state);
    }
}
