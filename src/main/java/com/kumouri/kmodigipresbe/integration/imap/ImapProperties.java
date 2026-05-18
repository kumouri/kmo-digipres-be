package com.kumouri.kmodigipresbe.integration.imap;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IMAP inbound-poll configuration (Phase H — H.3).
 *
 * <h2>No-live-IMAP boundary (§7 hard line)</h2>
 * {@link #enabled} defaults to {@code false} so no poll cycle is ever started
 * by CI or the default application context. {@link #host} defaults to a
 * <strong>non-routable {@code .invalid} host</strong>; every field is
 * configurable via {@code kmosf.imap.inbound.*}. No host is hardcoded.
 * Wiring a live IMAP server or credential is a separate human action — NOT
 * authorized by the implementation loop.
 *
 * <p>The {@code ImapInboundPoller} is gated on
 * {@code @ConditionalOnProperty(prefix="kmosf.imap.inbound", name="enabled",
 * matchIfMissing=false)} — the default-OFF posture ensures no IMAP connect
 * occurs in CI or any test run unless explicitly opted in.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.imap.inbound")
public class ImapProperties {

    /**
     * Whether the IMAP inbound poller is active. Defaults to {@code false}
     * (default-OFF hard requirement — no live IMAP in CI or the default context).
     */
    private boolean enabled = false;

    /**
     * IMAP server host. Defaults to a non-routable {@code .invalid} TLD so any
     * accidental non-gated connect fails fast rather than reaching a real server.
     */
    private String host = "imap.mail.invalid";

    /** IMAP server port. Default 993 (IMAPS). */
    private int port = 993;

    /** IMAP login username / email address. */
    private String username = "";

    /** IMAP login password. */
    private String password = "";

    /** IMAP folder to poll. Default {@code INBOX}. */
    private String folder = "INBOX";

    /**
     * Fixed-rate poll interval in milliseconds. Default 60 000 ms (1 minute).
     * Honoured as the {@code fixedRateString} in {@code @Scheduled} via
     * {@code ${kmosf.imap.inbound.poll-interval-ms:60000}}.
     */
    private long pollIntervalMs = 60_000L;
}
