package com.kumouri.kmodigipresbe.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;

/**
 * Transparently rewrites legacy {@code /api/*} requests to {@code /api/v1/*} so that
 * clients built against the old un-versioned base path continue to work during the
 * Phase A→B migration window.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><strong>Order({@link Ordered#HIGHEST_PRECEDENCE}):</strong> must run before
 *       Spring Security's {@code WebFilterChainProxy} (
 *       {@code DEFAULT_FILTER_ORDER = HIGHEST_PRECEDENCE + 50}) so that the rewritten
 *       path is what the security chain evaluates.</li>
 *   <li><strong>Loop guard:</strong> the {@code !startsWith("/api/v1/")} exclusion
 *       means already-versioned paths pass through untouched — no double-prefix.</li>
 *   <li><strong>Single {@code chain.filter}:</strong> no {@code switchIfEmpty} branch.
 *       {@link com.kumouri.kmodigipresbe.tenancy.TenantWebFilter} documents the
 *       double-run trap that arises when {@code switchIfEmpty} receives a
 *       {@code Mono<Void>}; this filter avoids it by choosing one code-path
 *       unconditionally.</li>
 *   <li><strong>No 302 redirect:</strong> a redirect would break POST bodies; a
 *       mutated-path forward preserves method + body entirely.</li>
 *   <li><strong>RFC 8594 Deprecation headers:</strong> {@code Deprecation: true},
 *       {@code Sunset} (fixed date ~90 days — Phase B removal), and a {@code Link}
 *       rel=successor-version pointing at the canonical path.</li>
 * </ul>
 *
 * <p><strong>Removal:</strong> this filter is scheduled for removal at the start of
 * Phase B once the FE is updated to call {@code /api/v1/*} directly.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyApiPathRewriteFilter implements WebFilter {

    /**
     * Sunset date: ~90 days from Phase A merge target (2025-08-15).
     * Update when the actual merge date is known.
     */
    private static final String SUNSET_DATE = "Sun, 15 Aug 2025 00:00:00 GMT";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();

        // Rewrite only paths that start with /api/ but NOT /api/v1/ (loop guard).
        if (rawPath.startsWith("/api/") && !rawPath.startsWith("/api/v1/")) {
            String rewrittenPath = rawPath.replaceFirst("^/api/", "/api/v1/");

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .path(rewrittenPath)
                    .build();

            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(mutated)
                    .build();

            // Add RFC 8594 deprecation headers to the response (written before chain runs
            // via the pre-response hook — headers must be set before the first write).
            mutatedExchange.getResponse().beforeCommit(() -> {
                mutatedExchange.getResponse().getHeaders().set("Deprecation", "true");
                mutatedExchange.getResponse().getHeaders().set("Sunset", SUNSET_DATE);
                mutatedExchange.getResponse().getHeaders().set(
                        "Link",
                        "<" + rewrittenPath + ">; rel=\"successor-version\"");
                return Mono.empty();
            });

            log.debug("Legacy path rewrite: {} -> {}", rawPath, rewrittenPath);
            return chain.filter(mutatedExchange);
        }

        return chain.filter(exchange);
    }
}
