package com.kumouri.kmodigipresbe.integration.stripe;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Stripe webhook signature scheme — the {@code Stripe-Signature} header is a
 * comma-separated list of {@code t=<timestamp>} and {@code v1=<hex-signature>}
 * pairs. We compute HMAC-SHA256 over {@code timestamp + "." + payload} with the
 * tenant's webhook signing secret and constant-time compare against the first
 * {@code v1=} value.
 *
 * <p>See <a href="https://docs.stripe.com/webhooks#verify-events">Stripe docs</a>.
 */
public final class StripeSignatureVerifier {

    /** Reject requests whose timestamp is more than this many seconds in the past. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private StripeSignatureVerifier() {
    }

    public static boolean verify(String signatureHeader, String payload, String secret,
                                 long nowEpochSeconds, long toleranceSeconds) {
        if (signatureHeader == null || payload == null || secret == null) return false;
        Long t = null;
        String v1 = null;
        for (String pair : signatureHeader.split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            } else if (kv[0].equals("v1") && v1 == null) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) return false;
        if (Math.abs(nowEpochSeconds - t) > toleranceSeconds) return false;
        String expected = hmacHex(secret, t + "." + payload);
        return constantTimeEquals(expected, v1);
    }

    public static boolean verify(String signatureHeader, String payload, String secret) {
        return verify(signatureHeader, payload, secret,
                System.currentTimeMillis() / 1000L, DEFAULT_TOLERANCE_SECONDS);
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
