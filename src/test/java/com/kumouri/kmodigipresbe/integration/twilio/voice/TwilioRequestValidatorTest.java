package com.kumouri.kmodigipresbe.integration.twilio.voice;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link TwilioRequestValidator} (Phase 1 — NMM voicemail-to-lead). Uses a
 * self-computed known-good signature fixture, the {@code StripeSignatureVerifierTest} shape.
 *
 * <p>No live Twilio — the {@code authToken} is a sandbox/test value and the signature is
 * computed locally (§7 hard boundary).
 */
class TwilioRequestValidatorTest {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_phase1";
    private static final String URL =
            "https://api-demo.kmosolutionsfoundry.com/api/v1/public/integrations/twilio/"
                    + "11111111-1111-1111-1111-111111111111/voicemail";

    /** Reference implementation of Twilio's scheme — the test's independent oracle. */
    private static String twilioSign(String authToken, String url, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(url);
        // Twilio sorts param NAMES; a TreeMap/sorted iteration is the canonical order.
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append(e.getValue() == null ? "" : e.getValue()));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] sig = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(sig);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Map<String, String> sampleParams() {
        // Intentionally out of alphabetical insertion order to prove sorting works.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("To", "+16185550100");
        params.put("From", "+16185550199");
        params.put("CallSid", "CA0000000000000000000000000000aaaa");
        params.put("RecordingSid", "RE0000000000000000000000000000bbbb");
        params.put("TranscriptionText", "Hi this is Jane, I have moles in my back yard, please call me.");
        params.put("TranscriptionStatus", "completed");
        return params;
    }

    @Test
    void validSignature_verifies() {
        Map<String, String> params = sampleParams();
        String sig = twilioSign(AUTH_TOKEN, URL, params);
        assertThat(TwilioRequestValidator.verify(sig, URL, params, AUTH_TOKEN)).isTrue();
    }

    @Test
    void wrongSignature_rejected() {
        Map<String, String> params = sampleParams();
        assertThat(TwilioRequestValidator.verify(
                "Zm9vYmFyZGVhZGJlZWY=", URL, params, AUTH_TOKEN)).isFalse();
    }

    @Test
    void tamperedParam_rejected() {
        Map<String, String> params = sampleParams();
        String sig = twilioSign(AUTH_TOKEN, URL, params);
        // Mutate a value after signing — must fail.
        params.put("From", "+19998887777");
        assertThat(TwilioRequestValidator.verify(sig, URL, params, AUTH_TOKEN)).isFalse();
    }

    @Test
    void tamperedUrl_rejected() {
        Map<String, String> params = sampleParams();
        String sig = twilioSign(AUTH_TOKEN, URL, params);
        String otherUrl = URL.replace("/voicemail", "/voice");
        assertThat(TwilioRequestValidator.verify(sig, otherUrl, params, AUTH_TOKEN)).isFalse();
    }

    @Test
    void wrongAuthToken_rejected() {
        Map<String, String> params = sampleParams();
        String sig = twilioSign(AUTH_TOKEN, URL, params);
        assertThat(TwilioRequestValidator.verify(sig, URL, params, "different_token")).isFalse();
    }

    @Test
    void nullOrBlankInputs_rejected() {
        Map<String, String> params = sampleParams();
        String sig = twilioSign(AUTH_TOKEN, URL, params);
        assertThat(TwilioRequestValidator.verify(null, URL, params, AUTH_TOKEN)).isFalse();
        assertThat(TwilioRequestValidator.verify("", URL, params, AUTH_TOKEN)).isFalse();
        assertThat(TwilioRequestValidator.verify(sig, null, params, AUTH_TOKEN)).isFalse();
        assertThat(TwilioRequestValidator.verify(sig, URL, params, null)).isFalse();
        assertThat(TwilioRequestValidator.verify(sig, URL, params, "")).isFalse();
    }

    @Test
    void paramOrderIndependent_sortedCanonicalization() {
        // Two maps with the same entries in different iteration order must canonicalize
        // identically (the buildSignedString sort is by name).
        Map<String, String> a = new LinkedHashMap<>();
        a.put("Alpha", "1");
        a.put("Bravo", "2");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("Bravo", "2");
        b.put("Alpha", "1");
        assertThat(TwilioRequestValidator.buildSignedString(URL, a))
                .isEqualTo(TwilioRequestValidator.buildSignedString(URL, b))
                .isEqualTo(URL + "Alpha1Bravo2");
    }

    @Test
    void noParams_signsUrlOnly() {
        Map<String, String> empty = new LinkedHashMap<>();
        assertThat(TwilioRequestValidator.buildSignedString(URL, empty)).isEqualTo(URL);
        String sig = twilioSign(AUTH_TOKEN, URL, empty);
        assertThat(TwilioRequestValidator.verify(sig, URL, empty, AUTH_TOKEN)).isTrue();
    }
}
