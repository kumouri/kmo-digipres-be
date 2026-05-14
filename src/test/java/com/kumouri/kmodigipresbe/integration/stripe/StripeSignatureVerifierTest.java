package com.kumouri.kmodigipresbe.integration.stripe;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class StripeSignatureVerifierTest {

    @Test
    void validSignatureWithinTolerance_verifies() {
        String secret = "whsec_test_supersecret";
        String body = "{\"id\":\"evt_123\",\"type\":\"payment_intent.succeeded\"}";
        long t = 1717000000L;
        String sig = hmacHex(secret, t + "." + body);
        String header = "t=" + t + ",v1=" + sig;

        assertThat(StripeSignatureVerifier.verify(header, body, secret, t, 300)).isTrue();
    }

    @Test
    void wrongSignature_rejected() {
        String secret = "whsec_test_supersecret";
        String body = "{\"id\":\"evt_123\"}";
        long t = 1717000000L;
        String header = "t=" + t + ",v1=deadbeef";
        assertThat(StripeSignatureVerifier.verify(header, body, secret, t, 300)).isFalse();
    }

    @Test
    void timestampOutsideTolerance_rejected() {
        String secret = "whsec_test_supersecret";
        String body = "{\"id\":\"evt_123\"}";
        long t = 1717000000L;
        String sig = hmacHex(secret, t + "." + body);
        String header = "t=" + t + ",v1=" + sig;
        long now = t + 1000;
        assertThat(StripeSignatureVerifier.verify(header, body, secret, now, 300)).isFalse();
    }

    @Test
    void missingV1_rejected() {
        assertThat(StripeSignatureVerifier.verify("t=1717000000", "body", "secret",
                1717000000L, 300)).isFalse();
    }

    @Test
    void malformedHeader_rejected() {
        assertThat(StripeSignatureVerifier.verify("garbage", "body", "secret",
                1717000000L, 300)).isFalse();
        assertThat(StripeSignatureVerifier.verify(null, "body", "secret",
                1717000000L, 300)).isFalse();
    }

    @Test
    void tamperedBody_rejected() {
        String secret = "whsec_test_supersecret";
        String original = "{\"id\":\"evt_123\"}";
        long t = 1717000000L;
        String sig = hmacHex(secret, t + "." + original);
        String header = "t=" + t + ",v1=" + sig;
        String tampered = "{\"id\":\"evt_999\"}";
        assertThat(StripeSignatureVerifier.verify(header, tampered, secret, t, 300)).isFalse();
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
