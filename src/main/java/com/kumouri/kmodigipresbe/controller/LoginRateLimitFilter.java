package com.kumouri.kmodigipresbe.controller;

import org.springframework.beans.factory.annotation.Value;
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
 * attempts from a single source IP (default {@value #DEFAULT_MAX_REQUESTS} per
 * {@value #DEFAULT_WINDOW_SECONDS} seconds, tunable via
 * {@code kmosf.login-rate-limit.max-requests} / {@code .window-seconds}); over the cap
 * returns {@code 429 Too Many Requests} before the handler runs. (The test profile sets a
 * very high cap so the shared-context IT suite — 20+ classes logging in from one loopback
 * IP — never trips it; a dedicated limit test can override low via {@code @TestPropertySource}.)
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
    static final int DEFAULT_MAX_REQUESTS = 10;
    static final int DEFAULT_WINDOW_SECONDS = 60;

    private final int maxRequests;
    private final int windowSeconds;
    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(
            @Value("${kmosf.login-rate-limit.max-requests:10}") int maxRequests,
            @Value("${kmosf.login-rate-limit.window-seconds:60}") int windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

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
        Instant windowStart = now.minus(Duration.ofSeconds(windowSeconds));
        Deque<Instant> hits = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            Iterator<Instant> it = hits.iterator();
            while (it.hasNext()) {
                if (it.next().isBefore(windowStart)) it.remove();
                else break;
            }
            if (hits.size() >= maxRequests) return false;
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
