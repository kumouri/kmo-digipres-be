package com.kumouri.kmodigipresbe.integration.documenso;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the Documenso webhook signature (Phase F — F-D7).
 *
 * <h2>ASSUMPTION (F-D7 — documented known unknown)</h2>
 * The exact signature scheme Documenso uses is a <strong>known unknown</strong>
 * deliberately not specified in the ultraplan. This verifier implements the
 * simplest defensible scheme that matches the Stripe-family convention:
 * <ul>
 *   <li>Algorithm: HMAC-SHA256</li>
 *   <li>Input: the <em>raw request body bytes</em> (UTF-8)</li>
 *   <li>Key: the tenant's {@code webhookSigningSecret}
 *       (from {@code IntegrationConnection.secrets})</li>
 *   <li>Output format: lowercase hex-encoded HMAC digest</li>
 *   <li>Header: {@code X-Documenso-Signature} carrying the hex digest</li>
 * </ul>
 * This assumption is <strong>contained to exactly this class</strong> — the
 * only place the digest scheme lives. Correcting it against a real Documenso
 * deployment requires changing only {@link #computeHmacHex} (and the test
 * signing helper in the IT). The signature header name is assumed in
 * {@code DocumensoWebhookController}'s {@code @RequestHeader} annotation.
 *
 * <h2>Constant-time comparison</h2>
 * Uses {@link MessageDigest#isEqual} to avoid timing side-channels —
 * identical to {@code StripeSignatureVerifier} (the mandated structural mirror,
 * F-D7 §9).
 *
 * <p>No timestamp tolerance is part of the assumed Documenso scheme (unlike
 * Stripe's {@code t=} header). The clock-injectable overload is retained for
 * structural parity and future extensibility (if the real Documenso scheme
 * turns out to include a timestamp, the overload slot is already present).
 *
 * <p>No live Documenso anywhere — signatures are verified with a test signing
 * secret in tests (§7 hard boundary).
 */
public final class DocumensoSignatureVerifier {

    private DocumensoSignatureVerifier() {
    }

    /**
     * Verifies the {@code X-Documenso-Signature} header value against the HMAC
     * of the raw request body.
     *
     * @param signatureHeader value of the {@code X-Documenso-Signature} header
     *                        (may be {@code null} — treated as invalid)
     * @param rawBody         the raw request body string (UTF-8)
     * @param secret          the tenant's {@code webhookSigningSecret}
     * @return {@code true} if and only if the signature is valid
     */
    public static boolean verify(String signatureHeader, String rawBody, String secret) {
        return verify(signatureHeader, rawBody, secret, System.currentTimeMillis() / 1000L);
    }

    /**
     * Clock-injectable overload for testability (structural parity with
     * {@code StripeSignatureVerifier}; the {@code nowEpochSeconds} param is
     * unused in the current assumed scheme — retained for future extensibility).
     */
    public static boolean verify(String signatureHeader, String rawBody, String secret,
                                 long nowEpochSeconds) {
        if (signatureHeader == null || rawBody == null || secret == null) return false;
        if (signatureHeader.isBlank() || secret.isBlank()) return false;
        String expected = computeHmacHex(secret, rawBody);
        return constantTimeEquals(expected, signatureHeader.trim());
    }

    /**
     * Computes HMAC-SHA256 over {@code payload} keyed by {@code secret},
     * returns the lowercase hex-encoded digest.
     *
     * <p>This is the <strong>only place the digest scheme is implemented</strong>
     * (F-D7 assumption boundary). Correcting against a real Documenso deployment
     * is a change to this method only.
     */
    private static String computeHmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
