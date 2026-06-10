package com.kumouri.kmodigipresbe.integration.square;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix BE-14 — the Square webhook verifier must fail closed on a BLANK key/URL (not just
 * null), and accept a correctly-signed payload. Pure (no Docker).
 */
class SquareSignatureVerifierTest {

    private static final String URL = "https://api.kmosf.test/public/integrations/square/webhook";
    private static final String BODY = "{\"type\":\"payment.completed\",\"event_id\":\"evt_1\"}";
    private static final String KEY = "sq_webhook_signature_key_test";

    private static String sign(String key, String url, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal((url + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void validSignature_verifies() {
        String header = sign(KEY, URL, BODY);
        assertThat(SquareSignatureVerifier.verify(header, URL, BODY, KEY)).isTrue();
    }

    @Test
    void blankKey_rejected_failClosed() {
        // A blank key must fail closed (BE-14) — the verifier short-circuits on the blank key
        // before any HMAC, so the header value is irrelevant.
        String anyHeader = sign(KEY, URL, BODY);
        assertThat(SquareSignatureVerifier.verify(anyHeader, URL, BODY, "")).isFalse();
        assertThat(SquareSignatureVerifier.verify(anyHeader, URL, BODY, "   ")).isFalse();
    }

    @Test
    void blankNotificationUrl_rejected() {
        String anyHeader = sign(KEY, URL, BODY);
        assertThat(SquareSignatureVerifier.verify(anyHeader, "", BODY, KEY)).isFalse();
        assertThat(SquareSignatureVerifier.verify(anyHeader, "   ", BODY, KEY)).isFalse();
    }

    @Test
    void nullInputs_rejected() {
        assertThat(SquareSignatureVerifier.verify(null, URL, BODY, KEY)).isFalse();
        assertThat(SquareSignatureVerifier.verify("sig", URL, null, KEY)).isFalse();
        assertThat(SquareSignatureVerifier.verify("sig", URL, BODY, null)).isFalse();
    }

    @Test
    void wrongSignature_rejected() {
        assertThat(SquareSignatureVerifier.verify("not-the-sig", URL, BODY, KEY)).isFalse();
    }

    @Test
    void tamperedBody_rejected() {
        String header = sign(KEY, URL, BODY);
        assertThat(SquareSignatureVerifier.verify(header, URL, BODY + "tampered", KEY)).isFalse();
    }
}
