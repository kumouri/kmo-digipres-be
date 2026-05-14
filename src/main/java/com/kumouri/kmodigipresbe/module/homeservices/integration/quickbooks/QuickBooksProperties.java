package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global QuickBooks Online configuration. Per-tenant credentials live in
 * {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection} (provider
 * {@code "quickbooks"}); these properties carry only the values that are the
 * same for every tenant — the OAuth app's {@code clientId}/{@code clientSecret}
 * (KMOSF's Intuit developer app), the redirect URI we host, the Intuit API
 * base URLs (overridable to point WireMock at the integration tests), and the
 * HMAC secret used to sign the OAuth {@code state} parameter so the callback
 * proves which tenant initiated the consent flow.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.integrations.quickbooks")
public class QuickBooksProperties {

    /** Master toggle. When {@code false}, the auto-configuration registers nothing. */
    private boolean enabled = false;

    /** Intuit OAuth2 app client id. */
    private String clientId = "";

    /** Intuit OAuth2 app client secret. */
    private String clientSecret = "";

    /**
     * The redirect URI registered with Intuit for the OAuth consent flow. Intuit
     * sends the user here after they grant or deny consent. Must exactly match
     * what's configured on the Intuit developer dashboard.
     */
    private String redirectUri = "";

    /**
     * Intuit's production API base URL — POST {@code /v3/company/{realmId}/...}
     * Override in tests to point at WireMock.
     */
    private String apiBaseUrl = "https://quickbooks.api.intuit.com";

    /**
     * Intuit's OAuth2 base URL — token exchange + refresh-token endpoints.
     * Override in tests to point at WireMock.
     */
    private String oauthBaseUrl = "https://oauth.platform.intuit.com";

    /**
     * Intuit's consent / authorization URL. Distinct from {@link #oauthBaseUrl}
     * because the consent page lives on a separate Intuit host.
     */
    private String authorizationBaseUrl = "https://appcenter.intuit.com";

    /**
     * HMAC-SHA256 key used to sign the OAuth2 {@code state} parameter. The
     * callback verifies the signature so an attacker cannot forge a callback
     * for another tenant. If blank, a fresh random key is generated at boot
     * (issued states invalidate on restart — acceptable for dev, set in prod).
     */
    private String stateSigningSecret = "";

    /** How many seconds the signed {@code state} parameter is valid for. */
    private long stateTtlSeconds = 600;

    /**
     * If the access token would expire within this many seconds, the sync
     * service refreshes it before making the outbound API call.
     */
    private long refreshSkewSeconds = 300;
}
