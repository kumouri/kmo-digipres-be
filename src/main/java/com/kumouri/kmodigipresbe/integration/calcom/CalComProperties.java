package com.kumouri.kmodigipresbe.integration.calcom;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Cal.com configuration (Phase H — H.2). Per-tenant webhook signing
 * secrets live in {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection}
 * (provider {@code "calcom"}) — the {@code webhookSigningSecret} field.
 * These properties carry only what is the same for every tenant.
 *
 * <h2>No-live-Cal.com boundary (§7 hard line)</h2>
 * {@link #apiBaseUrl} defaults to a <strong>non-routable {@code .invalid} host</strong>
 * so no code path accidentally contacts a real Cal.com API endpoint. In every
 * test/CI run {@code kmosf.calcom.api-base-url} is overridden to the WireMock
 * base URL via {@code @DynamicPropertySource}. No host is hardcoded; webhook
 * signing secrets are sandbox/test-only values in every test fixture. Wiring a
 * live Cal.com deployment or credential is a separate human action — NOT
 * authorized by the implementation loop.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.calcom")
public class CalComProperties {

    /**
     * Cal.com API base URL. Defaults to a non-routable {@code .invalid} host so
     * no test can accidentally reach a real Cal.com endpoint (§7 hard boundary).
     * Overridden to the WireMock base URL in all test/CI runs.
     */
    private String apiBaseUrl = "https://api.cal.invalid";

    /** Per-request HTTP timeout (seconds). */
    private long requestTimeoutSeconds = 10;
}
