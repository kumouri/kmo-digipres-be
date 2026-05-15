package com.kumouri.kmodigipresbe.integration.square;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Square configuration. Per-tenant credentials (access token, refresh token,
 * merchantId) live in {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection}
 * (provider {@code "square"}). These properties carry values constant across all
 * tenants — the OAuth app credentials, redirect URI, API base URLs (overridable
 * for WireMock in tests), and the webhook signature key.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.integrations.square")
public class SquareProperties {

    /** Master toggle. When {@code false}, the auto-configuration registers nothing. */
    private boolean enabled = false;

    /** Square OAuth2 app application id (client id). */
    private String clientId = "";

    /** Square OAuth2 app application secret (client secret). */
    private String clientSecret = "";

    /**
     * The redirect URI registered in the Square Developer Dashboard for the OAuth
     * consent flow. Must exactly match the registered value.
     */
    private String redirectUri = "";

    /**
     * Square Connect API base URL. Override in tests to point at WireMock.
     */
    private String apiBaseUrl = "https://connect.squareup.com";

    /**
     * HMAC-SHA256 key used to sign the OAuth2 {@code state} parameter. If blank, a
     * fresh random key is generated at boot (issued states invalidate on restart —
     * acceptable for dev, set in prod).
     */
    private String stateSigningSecret = "";

    /** How many seconds the signed {@code state} parameter is valid for. */
    private long stateTtlSeconds = 600;

    /**
     * The webhook signature key from the Square Developer Dashboard (Webhooks →
     * Signature Key). Used to verify incoming webhook payloads.
     */
    private String webhookSignatureKey = "";

    /**
     * The full public URL of the webhook endpoint — included in the Square signature
     * computation. Must match exactly what Square calls (e.g.
     * {@code https://api.kmosf.dev/public/integrations/square/webhook}).
     */
    private String webhookUrl = "";

    /**
     * If the access token would expire within this many seconds, the service refreshes
     * it before making the outbound API call.
     */
    private long refreshSkewSeconds = 300;
}
