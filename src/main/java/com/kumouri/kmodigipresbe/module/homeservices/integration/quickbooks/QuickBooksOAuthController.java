package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin endpoints for the QuickBooks Online OAuth2 consent flow.
 *
 * <ul>
 *   <li>{@code GET /integrations/quickbooks/oauth/start} — admin-only; returns
 *       the Intuit consent URL the admin should redirect to. The state parameter
 *       is signed with the global HMAC secret and carries the current tenant id.</li>
 *   <li>{@code GET /integrations/quickbooks/oauth/callback} — anonymous; Intuit
 *       redirects the user here with {@code code}, {@code state}, {@code realmId}.
 *       The signed state proves which tenant initiated; no auth header required.</li>
 * </ul>
 *
 * <p>The callback path is under the {@code /public/**} permitAll matcher so it
 * doesn't require the staff JWT — Intuit's redirect is a browser navigation and
 * carries no JWT.
 */
@RestController
@ConditionalOnProperty(prefix = "kmosf.integrations.quickbooks", name = "enabled")
@RequiredArgsConstructor
public class QuickBooksOAuthController {

    private final QuickBooksOAuthService oauth;

    /**
     * Returns the Intuit consent URL for the current tenant. Admin-only.
     */
    @RequestMapping(value = "/integrations/quickbooks/oauth/start", method = {
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST
    })
    public Mono<Map<String, String>> start() {
        return RoleGuard.requireRole("ADMIN")
                .then(TenantContextHolder.required())
                .flatMap(ctx -> oauth.buildAuthorizationUrl(ctx.tenantId()))
                .map(url -> Map.of("authorizationUrl", url));
    }

    /**
     * Intuit redirects here after consent. Anonymous endpoint; the signed
     * {@code state} parameter is the tenant binding.
     */
    @GetMapping("/public/integrations/quickbooks/oauth/callback")
    @ResponseStatus(HttpStatus.OK)
    public Mono<IntegrationConnection> callback(@RequestParam(name = "code", required = false) String code,
                                                @RequestParam(name = "state", required = false) String state,
                                                @RequestParam(name = "realmId", required = false) String realmId,
                                                @RequestParam(name = "error", required = false) String error) {
        if (error != null && !error.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "QBO consent denied or errored: " + error, 2810, 400));
        }
        return oauth.exchangeAuthCode(code, state, realmId);
    }
}
