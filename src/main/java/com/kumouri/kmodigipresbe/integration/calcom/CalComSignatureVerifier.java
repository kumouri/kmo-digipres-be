package com.kumouri.kmodigipresbe.integration.calcom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Cal.com webhook signature scheme. Cal.com signs webhook payloads using
 * HMAC-SHA256 over the raw body with the subscriber's signing secret, delivering
 * the hex-encoded digest in the {@code X-Cal-Signature-256} header.
 *
 * <p>The scheme is isolated here (F-D7 adapter-boundary discipline: every
 * assumption about the Cal.com signing scheme lives <em>only</em> in this class —
 * correcting against the real Cal.com spec is a one-file change). The WireMock
 * stub in {@code CalComWebhookIT} is the single contract coded-to.
 *
 * <p>Assumption (coded to the WireMock stub, recorded in the H.2 ledger):
 * Cal.com delivers a simple HMAC-SHA256 hex digest (no timestamp wrapping) in
 * {@code X-Cal-Signature-256}. If the real scheme adds a timestamp component,
 * the fix is to this class only.
 *
 * <p>Mirrors {@link com.kumouri.kmodigipresbe.integration.stripe.StripeSignatureVerifier}
 * in structure (constant-time comparison, null-safe, no exceptions exposed).
 *
 * <p>No live Cal.com anywhere — signing secrets are test/sandbox values in every
 * test fixture (§7 hard boundary).
 */
public final class CalComSignatureVerifier {

    private CalComSignatureVerifier() {
    }

    /**
     * Verifies a Cal.com {@code X-Cal-Signature-256} signature.
     *
     * @param signatureHeader the value of the {@code X-Cal-Signature-256} header
     * @param payload         the raw request body string
     * @param secret          the tenant's Cal.com webhook signing secret
     * @return {@code true} iff the signature is valid; {@code false} for any
     *         null/blank input or digest mismatch
     */
    public static boolean verify(String signatureHeader, String payload, String secret) {
        if (signatureHeader == null || payload == null || secret == null) return false;
        if (signatureHeader.isBlank() || secret.isBlank()) return false;
        // Strip optional "sha256=" prefix, e.g. "sha256=<hex>" or just "<hex>"
        String candidate = signatureHeader.startsWith("sha256=")
                ? signatureHeader.substring(7)
                : signatureHeader;
        String expected = hmacHex(secret, payload);
        return constantTimeEquals(expected, candidate);
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC failed", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
