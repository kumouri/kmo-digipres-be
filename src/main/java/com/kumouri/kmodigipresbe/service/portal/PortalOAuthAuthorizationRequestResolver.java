package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.tenancy.HostTenantResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Wraps Spring's {@link DefaultServerOAuth2AuthorizationRequestResolver} so the OAuth
 * {@code state} parameter carries a tenant identifier through the Google/Microsoft
 * round-trip — necessary because we use a single fixed redirect URI per provider and
 * the callback host can't tell us which tenant initiated.
 * <p>HMAC-signed state is encoded by {@link OAuthStateCodec}; the success handler
 * decodes and verifies it before any provisioning.
 */
@Component
@RequiredArgsConstructor
public class PortalOAuthAuthorizationRequestResolver
        implements ServerOAuth2AuthorizationRequestResolver {

    /** Spring's default authorization endpoint base path; we mount on {@code /portal/auth}. */
    private static final String PORTAL_AUTHORIZATION_BASE_URI =
            "/portal/auth/oauth2/authorization/{registrationId}";

    private final ReactiveClientRegistrationRepository clientRegistrationRepository;
    private final HostTenantResolver hostTenantResolver;
    private final OAuthStateCodec stateCodec;

    private DefaultServerOAuth2AuthorizationRequestResolver delegate() {
        return new DefaultServerOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
                        .pathMatchers(PORTAL_AUTHORIZATION_BASE_URI));
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
        return delegate().resolve(exchange)
                .flatMap(req -> signStateForTenant(exchange, req));
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange,
                                                    String clientRegistrationId) {
        return delegate().resolve(exchange, clientRegistrationId)
                .flatMap(req -> signStateForTenant(exchange, req));
    }

    private Mono<OAuth2AuthorizationRequest> signStateForTenant(
            ServerWebExchange exchange, OAuth2AuthorizationRequest base) {
        return hostTenantResolver.resolve(exchange)
                .map(tenant -> OAuth2AuthorizationRequest.from(base)
                        .state(stateCodec.encode(tenant.getId()))
                        .build());
    }
}
