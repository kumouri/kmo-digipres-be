package com.kumouri.kmodigipresbe.integration.twilio.voice;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Twilio request-signature scheme (Phase 1 — NMM voicemail-to-lead). Twilio signs every
 * webhook request it makes (incoming-call voice webhook, transcription/recording status
 * callback) and delivers the signature in the {@code X-Twilio-Signature} header.
 *
 * <p>The scheme (isolated here — the adapter boundary, exactly like
 * {@link com.kumouri.kmodigipresbe.integration.calcom.CalComSignatureVerifier} for Cal.com
 * and {@link com.kumouri.kmodigipresbe.integration.stripe.StripeSignatureVerifier} for
 * Stripe; every assumption about Twilio's signing scheme lives <em>only</em> in this class,
 * so correcting against the real Twilio spec is a one-file change):
 * <ol>
 *   <li>Take the <strong>full request URL</strong> Twilio POSTed to (scheme + host + path +
 *       any query string), exactly as Twilio was configured with it.</li>
 *   <li>For an {@code application/x-www-form-urlencoded} POST: sort the POST parameter
 *       <strong>names</strong> alphabetically, then append each name immediately followed by
 *       its value (no separators) to the URL string. A multi-valued parameter contributes
 *       each value, but Twilio webhook params are single-valued in practice.</li>
 *   <li>Compute {@code HMAC-SHA1} of that concatenated string keyed with the tenant's Twilio
 *       {@code authToken}, then Base64-encode the raw digest.</li>
 *   <li>Constant-time-compare the Base64 result against the {@code X-Twilio-Signature}
 *       header value.</li>
 * </ol>
 *
 * <p>See <a href="https://www.twilio.com/docs/usage/security#validating-requests">Twilio
 * request validation</a>. The WireMock/self-signed fixture in the Phase-1 ITs +
 * {@code TwilioRequestValidatorTest} is the single contract coded-to.
 *
 * <p>No live Twilio anywhere — the {@code authToken} is a sandbox/test value in every test
 * fixture and the signed string is computed locally (§7 hard boundary).
 */
public final class TwilioRequestValidator {

    private TwilioRequestValidator() {
    }

    /**
     * Verifies a Twilio {@code X-Twilio-Signature} over an
     * {@code application/x-www-form-urlencoded} POST.
     *
     * @param signatureHeader the value of the {@code X-Twilio-Signature} header
     *                        (Base64-encoded HMAC-SHA1)
     * @param fullUrl         the full request URL Twilio was configured to POST to
     *                        (scheme + host + path + query)
     * @param params          the POST form parameters (single-valued in practice)
     * @param authToken       the tenant's Twilio auth token (the HMAC key)
     * @return {@code true} iff the signature is valid; {@code false} for any null/blank
     *         required input or digest mismatch
     */
    public static boolean verify(String signatureHeader, String fullUrl,
                                 Map<String, String> params, String authToken) {
        if (signatureHeader == null || fullUrl == null || authToken == null) return false;
        if (signatureHeader.isBlank() || authToken.isBlank()) return false;

        String signedString = buildSignedString(fullUrl, params);
        String expected = hmacSha1Base64(authToken, signedString);
        return constantTimeEquals(expected, signatureHeader);
    }

    /**
     * Builds the exact string Twilio signs: the full URL followed by each POST parameter's
     * name immediately concatenated with its value, parameters sorted by name. Package-private
     * so {@code TwilioRequestValidatorTest} can assert the canonicalization directly.
     */
    static String buildSignedString(String fullUrl, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(fullUrl == null ? "" : fullUrl);
        if (params != null && !params.isEmpty()) {
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                String value = params.get(key);
                sb.append(key).append(value == null ? "" : value);
            }
        }
        return sb.toString();
    }

    private static String hmacSha1Base64(String authToken, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA1 failed", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
