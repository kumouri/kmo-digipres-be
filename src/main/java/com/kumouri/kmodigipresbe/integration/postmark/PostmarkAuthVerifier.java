package com.kumouri.kmodigipresbe.integration.postmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Postmark webhooks authenticate via HTTP Basic Auth — Postmark's webhook
 * configuration UI takes a username + password and Postmark sends
 * {@code Authorization: Basic <base64(user:pass)>} on every call. Unlike Stripe,
 * Postmark does not HMAC-sign the body.
 *
 * <p>This verifier parses the header and constant-time-compares the supplied
 * password to the tenant's stored {@code secrets.webhookBasicAuthPassword}. The
 * username is not checked (tenants may pick anything during Postmark setup); the
 * password IS the secret.
 */
public final class PostmarkAuthVerifier {

    private static final Base64.Decoder DEC = Base64.getDecoder();

    private PostmarkAuthVerifier() {
    }

    /**
     * @param header {@code Authorization} header value, e.g. {@code "Basic dXNlcjpwdw=="}
     * @param expectedPassword the tenant's stored Postmark webhook password
     */
    public static boolean verify(String header, String expectedPassword) {
        if (header == null || expectedPassword == null) return false;
        if (!header.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        String b64 = header.substring(6).trim();
        if (b64.isEmpty()) return false;
        byte[] decoded;
        try {
            decoded = DEC.decode(b64);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String pair = new String(decoded, StandardCharsets.UTF_8);
        int colon = pair.indexOf(':');
        if (colon < 0) return false;
        String suppliedPassword = pair.substring(colon + 1);
        return MessageDigest.isEqual(
                suppliedPassword.getBytes(StandardCharsets.UTF_8),
                expectedPassword.getBytes(StandardCharsets.UTF_8));
    }
}
