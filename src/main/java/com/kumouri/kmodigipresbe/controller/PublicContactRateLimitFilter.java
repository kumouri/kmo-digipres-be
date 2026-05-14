package com.kumouri.kmodigipresbe.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Rate-limits unauthenticated {@code POST /public/{tenantSlug}/contacts} requests
 * to {@value #MAX_REQUESTS} per {@value #WINDOW_SECONDS} seconds, keyed by source
 * IP + tenant slug. Other {@code /public/**} endpoints (notably
 * {@code /public/booking}, {@code /public/widget/**}) are deliberately not affected
 * — the path matcher only triggers on the contacts route.
 *
 * <p>Bucket storage is an in-process {@link ConcurrentHashMap}, which is fine for
 * a single-instance dev/prod-of-one deployment. TODO: replace with a Redis-backed
 * token bucket (or Bucket4j-with-redis) once we run multiple instances; the in-memory
 * map silently lets each replica grant a fresh quota per IP+tenant.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class PublicContactRateLimitFilter implements WebFilter {

    /** Path pattern: /public/{tenantSlug}/contacts — strict, so booking + widget aren't caught. */
    private static final Pattern PATH_PATTERN =
            Pattern.compile("^/public/[^/]+/contacts/?$");

    static final int MAX_REQUESTS = 10;
    static final int WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!"POST".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }
        String path = request.getPath().pathWithinApplication().value();
        if (!PATH_PATTERN.matcher(path).matches()) {
            return chain.filter(exchange);
        }

        String tenantSlug = extractTenantSlug(path);
        String key = clientIp(request) + "|" + tenantSlug;
        if (!tryConsume(key)) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean tryConsume(String key) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofSeconds(WINDOW_SECONDS));
        Deque<Instant> hits = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            Iterator<Instant> it = hits.iterator();
            while (it.hasNext()) {
                if (it.next().isBefore(windowStart)) it.remove();
                else break;
            }
            if (hits.size() >= MAX_REQUESTS) return false;
            hits.addLast(now);
            return true;
        }
    }

    private static String extractTenantSlug(String path) {
        // path = /public/{slug}/contacts(/)
        String[] parts = path.split("/");
        // parts[0] is "" because path starts with /, parts[1]="public", parts[2]={slug}
        return parts.length > 2 ? parts[2] : "";
    }

    private static String clientIp(ServerHttpRequest request) {
        String fwd = request.getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            // Trust only the leftmost token; this filter is behind whatever reverse
            // proxy the deployment uses, so the first hop is the real client.
            int comma = fwd.indexOf(',');
            return comma > 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
