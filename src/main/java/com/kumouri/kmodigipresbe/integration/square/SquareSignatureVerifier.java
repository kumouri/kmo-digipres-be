package com.kumouri.kmodigipresbe.integration.square;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Square webhook signature scheme — the {@code x-square-hmacsha256-signature}
 * header is Base64(HMAC-SHA256(notificationUrl + rawBody, webhookSignatureKey)).
 * The notification URL is the full webhook endpoint URL registered in the Square
 * Developer Dashboard; it must match exactly.
 *
 * <p>See <a href="https://developer.squareup.com/docs/webhooks/step3validate">
 * Square webhook validation docs</a>.
 */
public final class SquareSignatureVerifier {

    private SquareSignatureVerifier() {
    }

    public static boolean verify(String signatureHeader, String notificationUrl,
                                 String rawBody, String signatureKey) {
        if (signatureHeader == null || notificationUrl == null
                || rawBody == null || signatureKey == null) {
            return false;
        }
        String expected = hmacBase64(signatureKey, notificationUrl + rawBody);
        return constantTimeEquals(expected, signatureHeader);
    }

    private static String hmacBase64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
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
