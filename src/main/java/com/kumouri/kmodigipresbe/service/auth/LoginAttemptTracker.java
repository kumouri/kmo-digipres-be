package com.kumouri.kmodigipresbe.service.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security fix BE-06 — per-account login lockout. Tracks recent failed staff-login
 * attempts keyed by lowercased email in an in-process sliding window; once
 * {@value #MAX_FAILURES} failures accumulate within {@value #WINDOW_SECONDS} seconds the
 * account is "locked" and {@code AuthService} returns the generic 401 up front, doing NO
 * bcrypt/DB work, until the window rolls off. A successful login clears the counter.
 *
 * <p>This complements the per-IP {@code LoginRateLimitFilter}: the filter caps brute-force
 * <em>volume</em> from one source IP regardless of target account; this caps guesses
 * against a single <em>account</em> regardless of source IP (e.g. a distributed/botnet
 * password spray rotating IPs). Both are advisory, in-memory, single-instance controls
 * (the {@code PublicContactRateLimitFilter} precedent) — a multi-instance deployment wants
 * a shared store (Redis), tracked as a TODO there.
 *
 * <p>Lock state is deliberately NOT surfaced to the caller (no "account locked" message) —
 * that would be an enumeration oracle. The window is short enough not to be a usability
 * footgun for a legitimate user who mistyped, while still defeating automated guessing.
 */
@Component
public class LoginAttemptTracker {

    static final int MAX_FAILURES = 5;
    static final int WINDOW_SECONDS = 300;

    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /**
     * @return true if {@code key} (lowercased email) has at least {@value #MAX_FAILURES}
     * failures still inside the rolling {@value #WINDOW_SECONDS}-second window. Prunes
     * expired entries as a side effect.
     */
    public boolean isLocked(String key) {
        Deque<Instant> hits = failures.get(key);
        if (hits == null) {
            return false;
        }
        synchronized (hits) {
            pruneExpired(hits);
            return hits.size() >= MAX_FAILURES;
        }
    }

    /** Record one failed attempt for {@code key} (lowercased email). */
    public void recordFailure(String key) {
        Deque<Instant> hits = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            pruneExpired(hits);
            hits.addLast(Instant.now());
        }
    }

    /** Clear the failure counter for {@code key} on a successful login. */
    public void recordSuccess(String key) {
        failures.remove(key);
    }

    private static void pruneExpired(Deque<Instant> hits) {
        Instant windowStart = Instant.now().minus(Duration.ofSeconds(WINDOW_SECONDS));
        Iterator<Instant> it = hits.iterator();
        while (it.hasNext()) {
            if (it.next().isBefore(windowStart)) {
                it.remove();
            } else {
                break;
            }
        }
    }
}
