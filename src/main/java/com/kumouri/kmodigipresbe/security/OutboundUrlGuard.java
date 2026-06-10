package com.kumouri.kmodigipresbe.security;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Shared outbound-URL SSRF guard (security fixes BE-08, BE-09, BE-16). Validates that a
 * tenant-supplied / payload-supplied outbound URL is safe to fetch from the server:
 *
 * <ol>
 *   <li>parses as an absolute URL with an http(s) scheme (and, by default, requires
 *       {@code https});</li>
 *   <li>resolves the host and rejects it if <em>any</em> resolved IP is loopback,
 *       link-local (incl. the cloud metadata address {@code 169.254.169.254}),
 *       site-local / RFC-1918 private, unique-local IPv6 (ULA {@code fc00::/7}),
 *       multicast, or the wildcard/any-local address.</li>
 * </ol>
 *
 * <p><b>DNS-rebinding.</b> {@link #validate(String)} re-resolves on every call and is
 * invoked immediately before each outbound request (including each delivery retry), so a
 * record that was safe at write-time but is later repointed at an internal IP is caught at
 * use-time. (True pin-the-IP rebinding immunity would require the HTTP client to connect to
 * the exact validated address; with a pooled {@code WebClient} that re-resolves at connect
 * a narrow TOCTOU window remains — documented, accepted for this control. The block still
 * defeats the common "register an internal URL / repoint between calls" attack.)
 *
 * <p>Policy is {@link OutboundUrlGuardProperties}; production is strict (HTTPS + all
 * private ranges blocked). The test profile relaxes both so WireMock on loopback works.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundUrlGuard {

    /** Outbound URL blocked by the SSRF guard (400). */
    public static final int ERROR_CODE = 4700;

    /** The cloud instance-metadata service address (AWS/GCP/Azure/Hetzner link-local). */
    private static final String METADATA_IP = "169.254.169.254";

    private final OutboundUrlGuardProperties props;

    /**
     * Validate {@code rawUrl} reactively (the host resolution is blocking, so it runs on
     * {@code boundedElastic}). Emits the parsed {@link URI} when safe, or errors with a
     * {@link DigiPresBeException} ({@link #ERROR_CODE}, 400) when not. Intended to be
     * called right before the outbound request so it doubles as the connect-time re-check.
     */
    public Mono<URI> validate(String rawUrl) {
        return Mono.fromCallable(() -> validateSync(rawUrl))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Synchronous form (used by {@link #validate} and by unit tests). Performs blocking DNS
     * resolution — do not call directly on a Netty event-loop thread.
     */
    public URI validateSync(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw blocked("outbound URL is blank");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw blocked("outbound URL is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw blocked("outbound URL scheme must be http(s)");
        }
        if (props.isRequireHttps() && !scheme.equals("https")) {
            throw blocked("outbound URL must use https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked("outbound URL has no host");
        }

        if (props.isAllowPrivateNetworks()) {
            // Test/dev relaxation — skip the IP-range checks entirely (WireMock on loopback).
            return uri;
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw blocked("outbound URL host does not resolve");
        }
        for (InetAddress addr : resolved) {
            if (isBlockedAddress(addr)) {
                log.warn("OutboundUrlGuard: blocked outbound URL host={} resolved={} ({})",
                        host, addr.getHostAddress(), blockReason(addr));
                throw blocked("outbound URL resolves to a non-routable / internal address");
            }
        }
        return uri;
    }

    /** True if {@code addr} is in any range we never let the server reach outbound. */
    static boolean isBlockedAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()      // 169.254/16 (incl. metadata) + fe80::/10
                || addr.isSiteLocalAddress()      // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()
                || addr.isAnyLocalAddress()       // 0.0.0.0 / ::
                || isUniqueLocalIpv6(addr)        // fc00::/7 (not flagged site-local by JDK)
                || METADATA_IP.equals(addr.getHostAddress());
    }

    private static String blockReason(InetAddress addr) {
        if (METADATA_IP.equals(addr.getHostAddress())) return "metadata";
        if (addr.isLoopbackAddress()) return "loopback";
        if (addr.isLinkLocalAddress()) return "link-local";
        if (addr.isSiteLocalAddress()) return "private";
        if (isUniqueLocalIpv6(addr)) return "ULA";
        if (addr.isMulticastAddress()) return "multicast";
        if (addr.isAnyLocalAddress()) return "wildcard";
        return "blocked";
    }

    /**
     * IPv6 unique-local addresses ({@code fc00::/7} — {@code fc00::} … {@code fdff::}) are
     * the IPv6 analogue of RFC-1918 but {@link InetAddress#isSiteLocalAddress()} returns
     * false for them, so they need an explicit check (first byte {@code 0xFC} or {@code 0xFD}).
     */
    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) {
            return false;
        }
        int first = b[0] & 0xFF;
        return first == 0xFC || first == 0xFD;
    }

    private static DigiPresBeException blocked(String why) {
        return new DigiPresBeException("Outbound request blocked: " + why, ERROR_CODE, 400);
    }
}
