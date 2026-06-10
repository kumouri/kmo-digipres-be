package com.kumouri.kmodigipresbe.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy for {@link OutboundUrlGuard} (security fix BE-08 / BE-09 / BE-16 — outbound SSRF
 * guard). The production defaults are strict: HTTPS required and all private / loopback /
 * link-local / metadata ranges blocked.
 *
 * <p>The two relaxations exist for the test/dev profile only (set in
 * {@code src/test/resources/application-test.properties}) so the existing WireMock
 * integration tests — which legitimately call {@code http://localhost:<port>} — keep
 * working. A negative IT that must prove the guard blocks an internal target overrides
 * {@link #allowPrivateNetworks} back to {@code false} via {@code @TestPropertySource}.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.outbound-guard")
public class OutboundUrlGuardProperties {

    /**
     * When true (production default) a non-HTTPS outbound URL is rejected. Set false in
     * the test profile so WireMock {@code http://} stubs are reachable.
     */
    private boolean requireHttps = true;

    /**
     * When true (production default) a URL whose host resolves into a loopback,
     * link-local, site-local/private, unique-local (ULA), multicast, wildcard, or the
     * cloud metadata address (169.254.169.254) is rejected. Set true ONLY in the test
     * profile to allow WireMock on loopback. (Name reads "allow private networks"; it
     * is the master off-switch for the whole private/loopback/metadata block.)
     */
    private boolean allowPrivateNetworks = false;
}
