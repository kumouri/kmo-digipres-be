package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Intuit webhook signature scheme — the {@code intuit-signature} header is a
 * base64-encoded HMAC-SHA256 of the raw request body using the per-tenant
 * webhook verifier token stored on the tenant's {@code IntegrationConnection}.
 *
 * <p>Compare in constant time via {@link MessageDigest#isEqual}; never short-circuit
 * on prefix mismatch.
 *
 * <p>See Intuit's <a href="https://developer.intuit.com/app/developer/qbo/docs/develop/webhooks">
 * webhooks reference</a>.
 */
public final class QuickBooksSignatureVerifier {

    private QuickBooksSignatureVerifier() {
    }

    public static boolean verify(String signatureHeader, String payload, String verifierToken) {
        if (signatureHeader == null || signatureHeader.isBlank()) return false;
        if (payload == null) return false;
        if (verifierToken == null || verifierToken.isBlank()) return false;
        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signatureHeader.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(verifierToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }
}
