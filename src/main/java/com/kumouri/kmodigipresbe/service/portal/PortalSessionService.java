package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.config.PortalProperties;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;

/**
 * Shared session-issuance logic for magic-link and passkey controllers — mints the
 * portal JWT and writes it as the configured cookie on the response. OAuth goes
 * through {@link PortalOAuthAuthenticationSuccessHandler} which inlines the same
 * shape (kept inline there to avoid threading {@code WebFilterExchange} through this
 * helper).
 */
@Service
@RequiredArgsConstructor
public class PortalSessionService {

    private final JwtTokenService jwtTokenService;
    private final PortalProperties portalProperties;

    public String issueSession(User user, ServerWebExchange exchange) {
        String token = jwtTokenService.mint(user);
        ResponseCookie cookie = ResponseCookie.from(portalProperties.jwtCookieName(), token)
                .httpOnly(true)
                .secure(portalProperties.jwtCookieSecure())
                .sameSite("Lax")
                .path("/")
                .domain(blankToNull(portalProperties.jwtCookieDomain()))
                .maxAge(Duration.ofHours(12))
                .build();
        exchange.getResponse().addCookie(cookie);
        return token;
    }

    public void clearSession(ServerWebExchange exchange) {
        ResponseCookie cookie = ResponseCookie.from(portalProperties.jwtCookieName(), "")
                .httpOnly(true)
                .secure(portalProperties.jwtCookieSecure())
                .sameSite("Lax")
                .path("/")
                .domain(blankToNull(portalProperties.jwtCookieDomain()))
                .maxAge(Duration.ZERO)
                .build();
        exchange.getResponse().addCookie(cookie);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
