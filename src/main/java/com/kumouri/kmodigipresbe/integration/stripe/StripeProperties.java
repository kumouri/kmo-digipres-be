package com.kumouri.kmodigipresbe.integration.stripe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Stripe configuration (Phase E — E-D9). Per-tenant credentials live in
 * {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection} (provider
 * {@code "stripe"}) — the {@code apiKey} (used by {@code StripeCheckoutService})
 * and the {@code webhookSigningSecret} (used by {@code StripeWebhookService}).
 * These properties carry only what is the same for every tenant.
 *
 * <h2>No-live-money boundary (§7 hard line)</h2>
 * {@link #apiBaseUrl} defaults to {@code https://api.stripe.com} but is
 * <strong>configurable</strong> precisely so every test / CI run points it at
 * WireMock ({@code kmosf.stripe.api-base-url=<wireMock.baseUrl()>}). No code path
 * hardcodes {@code api.stripe.com}; tests use a sandbox-shaped fake
 * ({@code sk_test_}) apiKey and a test webhook signing secret. Wiring a real /
 * live Stripe key, or initiating any real charge, is a separate human action — NOT
 * authorized by the implementation loop.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.stripe")
public class StripeProperties {

    /**
     * Stripe API base URL. Production default; <strong>overridden to the WireMock
     * base URL in every test/CI run</strong> so no test can reach the real Stripe
     * API or initiate a real charge (§7).
     */
    private String apiBaseUrl = "https://api.stripe.com";

    /** Per-checkout HTTP timeout (seconds). */
    private long requestTimeoutSeconds = 10;
}
