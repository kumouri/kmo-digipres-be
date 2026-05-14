package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.config.PortalProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.auth.UserIdentity;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Translates a successful OAuth2 / OIDC login into a portal session: provisions or
 * resolves the {@link User}, mints the same HS256 JWT the rest of the API trusts,
 * deposits it in an {@code HttpOnly} cookie, and 302s back to the FE.
 * <p>The tenant ID is read back out of the signed OAuth {@code state} parameter — the
 * Authentication object does not carry tenant context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalOAuthAuthenticationSuccessHandler implements ServerAuthenticationSuccessHandler {

    private final TenantRepository tenants;
    private final UserIdentityService userIdentityService;
    private final JwtTokenService jwtTokenService;
    private final OAuthStateCodec stateCodec;
    private final PortalProperties portalProperties;

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange,
                                              Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            return Mono.error(new DigiPresBeException(
                    "Unexpected authentication type for portal OAuth success handler",
                    1230, 500));
        }
        String state = webFilterExchange.getExchange().getRequest().getQueryParams().getFirst("state");
        java.util.UUID tenantId;
        try {
            tenantId = stateCodec.decode(state);
        } catch (DigiPresBeException ex) {
            return Mono.error(ex);
        }
        UserIdentity.Provider provider = switch (oauthToken.getAuthorizedClientRegistrationId()) {
            case "google" -> UserIdentity.Provider.GOOGLE;
            case "microsoft" -> UserIdentity.Provider.MICROSOFT;
            default -> null;
        };
        if (provider == null) {
            return Mono.error(new DigiPresBeException(
                    "Unknown OAuth registration: " + oauthToken.getAuthorizedClientRegistrationId(),
                    1231, 400));
        }

        ProviderClaims claims = extractClaims(oauthToken.getPrincipal());

        return tenants.findById(tenantId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "OAuth state referenced unknown tenant", 1232, 400)))
                .flatMap(tenant -> userIdentityService.findOrProvision(
                                tenant, provider, claims.sub(), claims.email(),
                                claims.emailVerified(), claims.displayName())
                        .map(user -> new Resolved(tenant, user))
                        .contextWrite(TenantContextHolder.write(preAuthContext(tenant))))
                .flatMap(resolved -> redirectWithCookie(webFilterExchange, resolved.user()));
    }

    private TenantContext preAuthContext(Tenant tenant) {
        return new TenantContext(tenant.getId(), null, Set.of());
    }

    private ProviderClaims extractClaims(OAuth2User principal) {
        if (principal instanceof OidcUser oidc) {
            return new ProviderClaims(
                    oidc.getSubject(),
                    oidc.getEmail(),
                    Boolean.TRUE.equals(oidc.getEmailVerified()),
                    oidc.getFullName() != null ? oidc.getFullName() : oidc.getEmail());
        }
        // Non-OIDC providers (unused today; Google + Microsoft both OIDC).
        String sub = principal.getAttribute("sub");
        String email = principal.getAttribute("email");
        Boolean verified = principal.getAttribute("email_verified");
        String name = principal.getAttribute("name");
        return new ProviderClaims(sub, email, Boolean.TRUE.equals(verified),
                name != null ? name : email);
    }

    private Mono<Void> redirectWithCookie(WebFilterExchange webFilterExchange, User user) {
        String token = jwtTokenService.mint(user);
        ResponseCookie cookie = ResponseCookie.from(portalProperties.jwtCookieName(), token)
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private record Resolved(Tenant tenant, User user) {}

    private record ProviderClaims(String sub, String email,
                                  boolean emailVerified, String displayName) {}
}
