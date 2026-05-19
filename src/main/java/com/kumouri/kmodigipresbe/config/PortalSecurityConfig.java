package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.controller.portal.PortalCookieBearerTokenConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.WebSessionOAuth2ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Client portal {@link SecurityWebFilterChain}. Matched on {@code /portal/**} ahead of
 * the staff chain (see {@link SecurityConfig}). Three sign-in paths share this chain:
 * <ul>
 *   <li>OAuth2 / OIDC (Google, Microsoft) — wired conditionally on configured providers.
 *       Handled by Spring Security's {@code oauth2Login()} with a custom state-signing
 *       authorization-request resolver and a JWT-minting success handler.</li>
 *   <li>Magic-link email — anonymous endpoints under {@code /portal/auth/magic-link*}.</li>
 *   <li>WebAuthn passkeys — anonymous endpoints under {@code /portal/auth/passkey/*}.</li>
 * </ul>
 * All three converge on the same HS256 JWT minted by {@code JwtTokenService} (with
 * the {@code portal} claim set to {@code CLIENT}) and read back via the same
 * {@link ReactiveJwtDecoder}. JWTs ride in either an {@code HttpOnly} cookie set by
 * the success handler or an {@code Authorization: Bearer} header; the
 * {@link PortalCookieBearerTokenConverter} normalizes both.
 */
@Configuration
@EnableConfigurationProperties(PortalProperties.class)
@RequiredArgsConstructor
public class PortalSecurityConfig {

    /**
     * The portal chain runs at higher precedence than the staff chain so it claims
     * {@code /portal/**} first. Anything outside {@code /portal/**} is invisible to
     * this chain.
     */
    @Bean
    @Order(50)
    public SecurityWebFilterChain portalSecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            CorsConfigurationSource corsSource,
            PortalCookieBearerTokenConverter cookieBearerTokenConverter,
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver,
            ServerAuthenticationSuccessHandler oauthSuccessHandler,
            PortalProperties portalProperties,
            OAuthRegistrationsSummary oauthRegistrationsSummary) {

        http.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/portal/**"))
                .cors(cors -> cors.configurationSource(corsSource))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.GET,
                                "/portal/auth/providers",
                                "/portal/auth/oauth2/authorization/**",
                                "/portal/auth/oauth2/callback/**").permitAll()
                        .pathMatchers(HttpMethod.POST,
                                "/portal/auth/magic-link",
                                "/portal/auth/magic-link/redeem",
                                "/portal/auth/passkey/login/start",
                                "/portal/auth/passkey/login/finish").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenConverter(cookieBearerTokenConverter)
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder)));

        if (oauthRegistrationsSummary.anyEnabled()) {
            http.oauth2Login(oauth -> oauth
                    .authenticationFailureHandler(failureHandler(portalProperties))
                    .authenticationSuccessHandler(oauthSuccessHandler)
                    .authorizationRequestResolver(authorizationRequestResolver)
                    .clientRegistrationRepository(clientRegistrationRepository)
                    .authorizationRequestRepository(
                            new WebSessionOAuth2ServerAuthorizationRequestRepository()));
        }

        return http.build();
    }

    private ServerAuthenticationFailureHandler failureHandler(PortalProperties props) {
        return new RedirectServerAuthenticationFailureHandler(
                props.failureRedirect() + "?error=oauth");
    }

    /**
     * Indicator bean exposed by {@link OAuthClientRegistrationConfig}. Used to decide
     * whether the chain should wire {@code oauth2Login()} at all — when no providers
     * have credentials configured the configurer is omitted entirely.
     *
     * <p>{@code zitadelEnabled} is added by H.6. The Zitadel portal registration is
     * present when {@code kmosf.portal.oauth.zitadel.*} is configured; the per-tenant
     * opt-in ({@code Tenant.zitadelOrgId != null}) is enforced in
     * {@link com.kumouri.kmodigipresbe.service.portal.PortalZitadelSuccessHandler},
     * not at the registration level.
     */
    public record OAuthRegistrationsSummary(boolean googleEnabled, boolean microsoftEnabled,
                                             boolean zitadelEnabled) {
        public boolean anyEnabled() {
            return googleEnabled || microsoftEnabled || zitadelEnabled;
        }
    }
}
