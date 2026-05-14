package com.kumouri.kmodigipresbe.controller.widget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

/**
 * Public-widget {@link SecurityWebFilterChain}. Matches {@code /public/widget/**}
 * ahead of the portal ({@code @Order(50)}) and staff ({@code @Order(LOWEST_PRECEDENCE)})
 * chains. All exchanges are permitted — auth happens at the controller via the
 * URL-bound HMAC token verified by {@code PublicWidgetTokenService}.
 *
 * <p>CSRF is disabled. Cross-origin form submissions from a static page are the
 * <em>point</em> of this chain; the token (which the tenant embedded in the
 * snippet) is the auth signal, not a same-origin cookie. The token's {@code widgetType}
 * claim guards against using a "form" token to hit a "booking" endpoint.
 */
@Configuration
public class PublicWidgetSecurityConfig {

    @Bean
    @Order(-3)
    public SecurityWebFilterChain publicWidgetFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/public/widget/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }
}
