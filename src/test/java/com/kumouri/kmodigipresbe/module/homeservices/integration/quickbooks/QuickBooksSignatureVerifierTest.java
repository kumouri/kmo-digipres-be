package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10d — Intuit's {@code intuit-signature} header is the base64-encoded
 * HMAC-SHA256 of the raw body, keyed by the per-tenant
 * {@code webhookVerifierToken}. Mirrors {@code StripeSignatureVerifierTest}.
 */
class QuickBooksSignatureVerifierTest {

    @Test
    void validSignatureVerifies() {
        String verifier = "qbo-verifier-secret";
        String body = "{\"eventNotifications\":[]}";
        String sig = sign(verifier, body);
        assertThat(QuickBooksSignatureVerifier.verify(sig, body, verifier)).isTrue();
    }

    @Test
    void tamperedBodyRejected() {
        String verifier = "qbo-verifier-secret";
        String original = "{\"eventNotifications\":[{\"realmId\":\"123\"}]}";
        String tampered = "{\"eventNotifications\":[{\"realmId\":\"999\"}]}";
        String sig = sign(verifier, original);
        assertThat(QuickBooksSignatureVerifier.verify(sig, tampered, verifier)).isFalse();
    }

    @Test
    void wrongVerifierRejected() {
        String body = "{\"eventNotifications\":[]}";
        String sig = sign("correct-secret", body);
        assertThat(QuickBooksSignatureVerifier.verify(sig, body, "wrong-secret")).isFalse();
    }

    @Test
    void missingHeaderRejected() {
        assertThat(QuickBooksSignatureVerifier.verify(null, "body", "secret")).isFalse();
        assertThat(QuickBooksSignatureVerifier.verify("", "body", "secret")).isFalse();
        assertThat(QuickBooksSignatureVerifier.verify("   ", "body", "secret")).isFalse();
    }

    @Test
    void missingVerifierRejected() {
        assertThat(QuickBooksSignatureVerifier.verify("sig", "body", null)).isFalse();
        assertThat(QuickBooksSignatureVerifier.verify("sig", "body", "")).isFalse();
    }

    @Test
    void garbageBase64Rejected() {
        // Not valid base64 — must not throw, just reject.
        assertThat(QuickBooksSignatureVerifier.verify("!!!not-base64!!!", "body", "secret")).isFalse();
    }

    @Test
    void nullPayloadRejected() {
        assertThat(QuickBooksSignatureVerifier.verify("c2ln", null, "secret")).isFalse();
    }

    private static String sign(String verifier, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(verifier.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
