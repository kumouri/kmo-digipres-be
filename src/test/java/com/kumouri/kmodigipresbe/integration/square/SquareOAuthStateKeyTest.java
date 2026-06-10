package com.kumouri.kmodigipresbe.integration.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Security fix BE-15 — on a blank {@code stateSigningSecret} the OAuth {@code state} HMAC must use
 * a fresh random per-boot key, NOT the old static literal {@code "dev-square-state-key"}. So a state
 * forged with that literal is rejected, while a state minted this boot still round-trips. Pure (no
 * Docker); the private state methods are exercised via reflection.
 */
class SquareOAuthStateKeyTest {

    private static final String OLD_LITERAL = "dev-square-state-key";

    private SquareOAuthService serviceWithBlankSecret() {
        SquareProperties props = new SquareProperties();
        props.setStateSigningSecret("");      // blank → random per-boot key (the fix)
        props.setStateTtlSeconds(600);
        return new SquareOAuthService(props, null, mock(WebClient.Builder.class), new ObjectMapper());
    }

    @Test
    void stateRoundTripsWithinABoot() throws Exception {
        SquareOAuthService svc = serviceWithBlankSecret();
        UUID tenantId = UUID.randomUUID();

        String state = (String) ReflectionTestUtils.invokeMethod(svc, "buildSignedState", tenantId);
        UUID extracted = ReflectionTestUtils.invokeMethod(svc, "extractTenantFromState", state);

        assertThat(extracted).isEqualTo(tenantId);
    }

    @Test
    void stateForgedWithOldLiteralKeyIsRejected() {
        SquareOAuthService svc = serviceWithBlankSecret();
        UUID tenantId = UUID.randomUUID();
        long expiry = System.currentTimeMillis() / 1000L + 600;
        String payload = tenantId + ":" + expiry;
        // Forge a state signed with the OLD hardcoded literal — must NOT verify under the random key.
        String forged = payload + ":" + hmacHex(OLD_LITERAL, payload);

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(svc, "extractTenantFromState", forged))
                .isInstanceOf(DigiPresBeException.class)
                .satisfies(e -> assertThat(((DigiPresBeException) e).getErrorCode()).isEqualTo(3001));
    }

    @Test
    void twoBlankSecretInstancesHaveIndependentRandomKeys() throws Exception {
        // A state minted by one blank-secret instance must not verify on another (each boots its own
        // random key) — proving the key is not a shared constant.
        SquareOAuthService a = serviceWithBlankSecret();
        SquareOAuthService b = serviceWithBlankSecret();
        UUID tenantId = UUID.randomUUID();

        String stateFromA = (String) ReflectionTestUtils.invokeMethod(a, "buildSignedState", tenantId);

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(b, "extractTenantFromState", stateFromA))
                .isInstanceOf(DigiPresBeException.class);
    }

    private static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
