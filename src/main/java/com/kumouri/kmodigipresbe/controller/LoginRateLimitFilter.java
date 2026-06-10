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

/**
 * Security fix BE-06 — per-IP throttle on {@code POST /auth/login}. Caps staff-login
 * attempts from a single source IP to {@value #MAX_REQUESTS} per {@value #WINDOW_SECONDS}
 * seconds; over the cap returns {@code 429 Too Many Requests} before the handler runs.
 *
 * <p>Mirrors {@link PublicContactRateLimitFilter} exactly (in-process sliding-window
 * {@link ConcurrentHashMap}, X-Forwarded-For-aware client IP, single-instance scope — the
 * same Redis-backed TODO applies). It complements the per-account
 * {@link com.kumouri.kmodigipresbe.service.auth.LoginAttemptTracker} lockout: this bounds
 * brute-force <em>volume</em> from one IP across any accounts; the tracker bounds guesses
 * against one <em>account</em> across any IPs.
 *
 * <p>Ordered just after the public-contact limiter ({@code HIGHEST_PRECEDENCE + 100}) at
 * {@code +101} so it runs early, before auth/tenant resolution — a throttled request never
 * touches the DB. {@code /auth/login} is on the permitAll surface, so no security-context
 * interaction is needed here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 101)
public class LoginRateLimitFilter implements WebFilter {

    static final String LOGIN_PATH = "/auth/login";
    static final int MAX_REQUESTS = 10;
    static final int WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!"POST".equalsIgnoreCase(request.getMethod().name())
                || !LOGIN_PATH.equals(request.getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }
        if (!tryConsume(clientIp(request))) {
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

    private static String clientIp(ServerHttpRequest request) {
        String fwd = request.getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return comma > 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
