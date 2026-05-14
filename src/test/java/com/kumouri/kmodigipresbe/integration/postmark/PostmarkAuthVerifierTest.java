package com.kumouri.kmodigipresbe.integration.postmark;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PostmarkAuthVerifierTest {

    private static String basic(String user, String pass) {
        String b64 = Base64.getEncoder().encodeToString((user + ":" + pass)
                .getBytes(StandardCharsets.UTF_8));
        return "Basic " + b64;
    }

    @Test
    void validBasicAuthAccepted() {
        assertThat(PostmarkAuthVerifier.verify(basic("postmark", "s3cret"), "s3cret"))
                .isTrue();
    }

    @Test
    void wrongPasswordRejected() {
        assertThat(PostmarkAuthVerifier.verify(basic("postmark", "wrong"), "expected"))
                .isFalse();
    }

    @Test
    void usernameIsIgnored() {
        // Postmark UI lets the tenant pick any username; password is the secret.
        assertThat(PostmarkAuthVerifier.verify(basic("anything", "s3cret"), "s3cret"))
                .isTrue();
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertThat(PostmarkAuthVerifier.verify(basic("u", "s3cret").replace("Basic", "BASIC"), "s3cret"))
                .isTrue();
    }

    @Test
    void rejectsNullOrEmptyOrMalformed() {
        assertThat(PostmarkAuthVerifier.verify(null, "s3cret")).isFalse();
        assertThat(PostmarkAuthVerifier.verify("Bearer xyz", "s3cret")).isFalse();
        assertThat(PostmarkAuthVerifier.verify("Basic ", "s3cret")).isFalse();
        assertThat(PostmarkAuthVerifier.verify("Basic not-base64!!!", "s3cret")).isFalse();
        // No colon in decoded payload.
        String noColon = "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes());
        assertThat(PostmarkAuthVerifier.verify(noColon, "s3cret")).isFalse();
    }

    @Test
    void rejectsNullExpectedPassword() {
        assertThat(PostmarkAuthVerifier.verify(basic("u", "p"), null)).isFalse();
    }
}
