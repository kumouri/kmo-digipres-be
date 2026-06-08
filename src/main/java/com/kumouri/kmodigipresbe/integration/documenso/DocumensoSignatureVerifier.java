package com.kumouri.kmodigipresbe.integration.documenso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies the Documenso webhook secret (Phase F — F-D7; corrected against the
 * real Documenso product).
 *
 * <h2>Real Documenso webhook scheme</h2>
 * Documenso does <strong>not</strong> HMAC-sign the webhook body. Instead it sends
 * the configured webhook secret <em>verbatim</em> as a plain string in the
 * {@code X-Documenso-Secret} request header. Verification is therefore a
 * <strong>constant-time equality</strong> of that header value against the tenant's
 * stored {@code webhookSigningSecret}
 * ({@code IntegrationConnection(provider="documenso").secrets["webhookSigningSecret"]}).
 *
 * <p>This is the <strong>only</strong> place the webhook-secret verification scheme
 * lives (the F-D7 adapter boundary — the digest-scheme analogue). The header NAME
 * ({@code X-Documenso-Secret}) lives only in {@code DocumensoWebhookController}'s
 * {@code @RequestHeader} annotation.
 *
 * <h2>Constant-time comparison</h2>
 * Uses {@link MessageDigest#isEqual} to avoid timing side-channels — identical in
 * spirit to {@code StripeSignatureVerifier} (the mandated structural mirror). A
 * plain {@code String.equals} would leak secret length / prefix via timing.
 *
 * <p>No live Documenso anywhere — the secret is a test secret in tests (§7 hard
 * boundary).
 */
public final class DocumensoSignatureVerifier {

    private DocumensoSignatureVerifier() {
    }

    /**
     * Verifies the {@code X-Documenso-Secret} header value against the tenant's
     * stored webhook secret via a constant-time equality.
     *
     * @param secretHeader value of the {@code X-Documenso-Secret} header
     *                     (may be {@code null} / blank — treated as invalid)
     * @param rawBody      the raw request body (unused in the real Documenso scheme —
     *                     Documenso authenticates the delivery with the shared
     *                     secret header, not a body signature; retained in the
     *                     signature so the service call site is scheme-agnostic)
     * @param secret       the tenant's {@code webhookSigningSecret}
     * @return {@code true} if and only if the header matches the stored secret
     */
    public static boolean verify(String secretHeader, String rawBody, String secret) {
        if (secretHeader == null || secret == null) return false;
        if (secretHeader.isBlank() || secret.isBlank()) return false;
        return constantTimeEquals(secret, secretHeader.trim());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
