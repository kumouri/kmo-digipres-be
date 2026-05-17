package com.kumouri.kmodigipresbe.integration.documenso;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Documenso configuration (Phase F — F-D5). Per-tenant credentials live in
 * {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection} (provider
 * {@code "documenso"}) — the {@code apiToken} (used by {@code DocumensoClient}) and
 * the {@code webhookSigningSecret} (used by {@code DocumensoWebhookService}). These
 * properties carry only what is the same for every tenant (global defaults and
 * timeouts).
 *
 * <h2>No-live-Documenso boundary (§7 hard line)</h2>
 * {@link #apiBaseUrl} defaults to a non-routable placeholder host — it is
 * <strong>never a real Documenso URL</strong>. Per-tenant base URL
 * ({@code IntegrationConnection.config["apiBaseUrl"]}) takes precedence in every
 * production and test path; in every test/CI run the configurable URL is pointed at
 * WireMock ({@code kmosf.documenso.api-base-url=<wireMock.baseUrl()>}). No code
 * path ever hardcodes a real Documenso host; tests use sandbox-shaped fake tokens
 * and test webhook signing secrets. Wiring a real Documenso deployment or a live
 * API token is a separate human action — NOT authorized by the implementation loop.
 *
 * <h2>Documenso-payload-format ASSUMPTION (F-D7)</h2>
 * <ul>
 *   <li>Signature header (assumed): {@code X-Documenso-Signature} — referenced ONLY
 *       in {@code DocumensoWebhookController} and {@code DocumensoSignatureVerifier}.
 *       Correcting against a real deployment = one {@code @RequestHeader} rename.</li>
 *   <li>Digest scheme (assumed): HMAC-SHA256 over the raw request body, hex-encoded,
 *       constant-time compare against the header value — {@code DocumensoSignatureVerifier}
 *       is the ONLY place the scheme lives.</li>
 *   <li>Payload shape (assumed): see {@code DocumensoEventAdapter.parse} — the ONLY
 *       place the wire format is assumed.</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "kmosf.documenso")
public class DocumensoProperties {

    /**
     * Global Documenso API base URL. Defaults to a non-routable placeholder —
     * <strong>never a real Documenso URL</strong> (§7 hard no-live-Documenso line).
     * In every test/CI run this is overridden to the WireMock base URL via
     * {@code @DynamicPropertySource}. Per-tenant
     * {@code IntegrationConnection.config["apiBaseUrl"]} takes precedence over this
     * global default in {@code DocumensoClient}.
     */
    private String apiBaseUrl = "https://documenso.internal.invalid";

    /** Per-request HTTP timeout (seconds). */
    private long requestTimeoutSeconds = 10;
}
