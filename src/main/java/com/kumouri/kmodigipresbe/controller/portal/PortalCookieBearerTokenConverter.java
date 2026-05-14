package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.config.PortalProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Pulls the JWT from either the configured portal cookie or an
 * {@code Authorization: Bearer} header. Cookie takes precedence — that's how OAuth
 * and magic-link flows ship the session back to the FE, and the FE never needs to
 * read the token directly (HttpOnly).
 */
@Component
@RequiredArgsConstructor
public class PortalCookieBearerTokenConverter implements ServerAuthenticationConverter {

    private final PortalProperties properties;
    private final ServerBearerTokenAuthenticationConverter bearer =
            new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(properties.jwtCookieName());
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
            return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
        }
        return bearer.convert(exchange);
    }
}
