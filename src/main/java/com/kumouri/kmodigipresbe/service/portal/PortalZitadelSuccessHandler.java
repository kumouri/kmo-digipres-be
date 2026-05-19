package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.config.PortalProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import com.kumouri.kmodigipresbe.tenancy.ZitadelClaimTenantResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Portal OAuth2 success handler for Zitadel OIDC logins (Phase H.6).
 *
 * <p>Called by {@link PortalOAuthAuthenticationSuccessHandler} when the
 * {@code OAuth2AuthenticationToken.authorizedClientRegistrationId == "zitadel"}.
 * Uses the A2 components verbatim: {@link ZitadelClaimTenantResolver} to resolve
 * the tenant + JIT-provision the user (same org-claim → tenant, role-map, and
 * {@link com.kumouri.kmodigipresbe.tenancy.ZitadelJustInTimeUserProvisioner} that A2
 * uses for the staff-API path). No new identity logic is introduced.
 *
 * <h2>Per-tenant opt-in gate (error code 3930)</h2>
 * <p>The OIDC token's {@code urn:zitadel:iam:org:id} claim is resolved via
 * {@link com.kumouri.kmodigipresbe.tenancy.ZitadelOrgTenantCache}; if no tenant
 * federates that org, the cache raises {@code 3301} (403, existing A2 behaviour —
 * unchanged). Error {@code 3930} (404, same-as-not-found) fires when
 * {@code Tenant.zitadelOrgId == null} is checked from a tenant that somehow resolved
 * without an org id — a secondary defence that should not occur in normal flow but
 * makes the opt-in invariant explicit.
 *
 * <h2>Non-opted local path byte-identical</h2>
 * <p>This handler only runs when Spring Security routes the callback for the
 * {@code zitadel} registration. A portal user in a tenant with
 * {@code zitadelOrgId == null} never initiates this flow — their magic-link/passkey
 * path is completely unaffected by this handler's presence, and the existing
 * {@link PortalOAuthAuthenticationSuccessHandler} branches for Google/Microsoft are
 * untouched (byte-identical to their pre-H.6 state).
 *
 * <h2>A2 reuse — exact call pattern</h2>
 * <pre>
 *   OidcUser → Jwt (reconstructed from OIDC ID-token claims)
 *       → ZitadelClaimTenantResolver.resolve(jwt)    // unchanged A2 component
 *             → ZitadelOrgTenantCache.resolve(orgId) // unchanged A2 component
 *             → RoleClaimMapper.mapRoles(jwt)         // unchanged A2 component
 *             → ZitadelJustInTimeUserProvisioner       // unchanged A2 component
 *       → TenantContext(tenantId, userId, roles)
 *   → UserRepository.findById(userId)
 *   → JwtTokenService.mint(user) → HttpOnly cookie → 302 to successRedirect
 * </pre>
 *
 * <p>New-resource-client lifecycle: no new HTTP client / pool / scheduler introduced.
 * All collaborators are already-managed Spring beans.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalZitadelSuccessHandler {

    private final ZitadelClaimTenantResolver zitadelClaimTenantResolver;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final JwtTokenService jwtTokenService;
    private final PortalProperties portalProperties;

    /**
     * Handles a successful Zitadel OIDC authentication.
     *
     * <p>Opt-in gate: if the resolved tenant has {@code zitadelOrgId == null} the
     * request is rejected with {@code 3930} (404 — same-as-not-found confidentiality
     * posture per H-D8 / G-D2). In practice this cannot occur because
     * {@link com.kumouri.kmodigipresbe.tenancy.ZitadelOrgTenantCache} already rejects
     * an org not federated to any tenant with {@code 3301} — 3930 is the explicit
     * secondary invariant.
     *
     * @param webFilterExchange the current exchange
     * @param authentication the successful {@link OAuth2AuthenticationToken} for the
     *                       {@code zitadel} registration
     */
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange,
                                              Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            return Mono.error(new DigiPresBeException(
                    "Unexpected authentication type for portal Zitadel success handler",
                    1230, 500));
        }
        if (!(token.getPrincipal() instanceof OidcUser oidcUser)) {
            return Mono.error(new DigiPresBeException(
                    "Portal Zitadel login did not produce an OidcUser principal",
                    3931, 401));
        }

        // Build a Spring Security Jwt from the OIDC ID token claims so we can call
        // ZitadelClaimTenantResolver.resolve(Jwt) verbatim — zero identity logic change.
        Jwt jwt = buildJwtFromOidcUser(oidcUser);

        return zitadelClaimTenantResolver.resolve(jwt)
                .flatMap(ctx -> {
                    // 3930 — secondary opt-in invariant (belt-and-suspenders after
                    // ZitadelOrgTenantCache which already rejects unknown orgs with 3301).
                    return tenants.findById(ctx.tenantId())
                            .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                    "Zitadel portal federation: tenant not found", 3930, 404)))
                            .flatMap(tenant -> {
                                if (tenant.getZitadelOrgId() == null) {
                                    // HARD: 3930 — federation not enabled for this tenant.
                                    return Mono.error(new DigiPresBeException(
                                            "Zitadel portal federation not enabled for tenant "
                                                    + tenant.getId() + " (zitadelOrgId == null)",
                                            3930, 404));
                                }
                                return resolveAndRedirect(webFilterExchange, ctx);
                            });
                });
    }

    private Mono<Void> resolveAndRedirect(WebFilterExchange webFilterExchange,
                                          TenantContext ctx) {
        if (ctx.userId() == null) {
            // No email on the token — JIT was skipped (ZitadelClaimTenantResolver sets
            // userId=null when email is absent). We cannot mint a portal session without
            // a user id — reject with 3931 rather than producing a session with no user.
            return Mono.error(new DigiPresBeException(
                    "Zitadel portal login: no usable email claim; cannot provision portal user",
                    3931, 401));
        }
        return users.findById(ctx.userId())
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Zitadel portal login: JIT-provisioned user not found after provision",
                        3931, 401)))
                .flatMap(user -> redirectWithCookie(webFilterExchange, user))
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> redirectWithCookie(WebFilterExchange webFilterExchange, User user) {
        String token = jwtTokenService.mint(user);
        ResponseCookie cookie = ResponseCookie
                .from(portalProperties.jwtCookieName(), token)
                .httpOnly(true)
                .secure(portalProperties.jwtCookieSecure())
                .sameSite("Lax")
                .path("/")
                .domain(blankToNull(portalProperties.jwtCookieDomain()))
                .maxAge(Duration.ofHours(12))
                .build();
        webFilterExchange.getExchange().getResponse().addCookie(cookie);
        webFilterExchange.getExchange().getResponse().setStatusCode(HttpStatus.FOUND);
        webFilterExchange.getExchange().getResponse().getHeaders()
                .setLocation(URI.create(portalProperties.successRedirect()));
        return webFilterExchange.getExchange().getResponse().setComplete();
    }

    /**
     * Reconstructs a Spring Security {@link Jwt} from the OIDC ID-token claims so
     * {@link ZitadelClaimTenantResolver#resolve(Jwt)} can be called verbatim (it reads
     * claims by name — the same claim map is present in both the A2 JWT-decoded path
     * and the portal OIDC path).
     *
     * <p>The token value is the serialized ID-token string from the OIDC handshake.
     * {@code issuedAt} and {@code expiresAt} are derived from the {@code iat}/{@code exp}
     * claims (always present in a Zitadel ID token); if absent they are defaulted to
     * avoid {@link Jwt} construction failures on unexpected tokens.
     */
    private static Jwt buildJwtFromOidcUser(OidcUser oidcUser) {
        Map<String, Object> claims = oidcUser.getAttributes();
        String tokenValue = oidcUser.getIdToken() != null
                ? oidcUser.getIdToken().getTokenValue()
                : "zitadel-portal-oidc";
        Instant issuedAt = oidcUser.getIssuedAt() != null
                ? oidcUser.getIssuedAt()
                : Instant.now().minusSeconds(10);
        Instant expiresAt = oidcUser.getExpiresAt() != null
                ? oidcUser.getExpiresAt()
                : Instant.now().plusSeconds(300);
        return Jwt.withTokenValue(tokenValue)
                .headers(h -> h.put("alg", "RS256"))
                .claims(c -> c.putAll(claims))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
