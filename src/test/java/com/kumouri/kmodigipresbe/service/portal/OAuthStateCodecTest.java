package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the OAuth state codec — the security boundary that maps an OAuth
 * callback back to the tenant that started the flow. Any change here that lets a
 * tampered or replayed state through is a cross-tenant escape, so this gets unit
 * tests separate from anything Spring-y.
 */
class OAuthStateCodecTest {

    private final SecretKeySpec key = new SecretKeySpec(
            "a-deterministic-test-secret-32-bytes-XXXXXXXXXXXXXX".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256");
    private final OAuthStateCodec codec = new OAuthStateCodec(key);

    @Test
    void roundTripPreservesTenantId() {
        UUID original = UUID.randomUUID();
        String state = codec.encode(original);
        assertThat(codec.decode(state)).isEqualTo(original);
    }

    @Test
    void tamperedPayloadIsRejected() {
        UUID original = UUID.randomUUID();
        String state = codec.encode(original);
        // Flip a char near the middle of the base64 payload. Not the last char —
        // base64-url-without-padding chars at the tail can carry filler bits whose
        // toggling doesn't change the decoded byte stream.
        int dot = state.indexOf('.');
        char[] chars = state.toCharArray();
        int target = dot / 2;
        chars[target] = chars[target] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void tamperedMacIsRejected() {
        UUID original = UUID.randomUUID();
        String state = codec.encode(original);
        int dot = state.indexOf('.');
        char[] chars = state.toCharArray();
        // Flip the first char of the MAC section.
        chars[dot + 1] = chars[dot + 1] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void truncatedStateIsRejected() {
        UUID original = UUID.randomUUID();
        String state = codec.encode(original);
        String truncated = state.substring(0, state.length() - 5);

        assertThatThrownBy(() -> codec.decode(truncated))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void emptyStateIsRejected() {
        assertThatThrownBy(() -> codec.decode(""))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> codec.decode(null))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void differentKeyRejectsState() {
        OAuthStateCodec other = new OAuthStateCodec(new SecretKeySpec(
                "different-key-bytes-32-byteszzzzzzzzzzzzzzzzzz".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        UUID original = UUID.randomUUID();
        String state = codec.encode(original);

        assertThatThrownBy(() -> other.decode(state))
                .isInstanceOf(DigiPresBeException.class);
    }
}
