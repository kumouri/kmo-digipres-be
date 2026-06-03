package com.kumouri.kmodigipresbe.integration.gbp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Google-Business-Profile configuration (NMM GBP review-reply automation). Per-tenant
 * credentials live in {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection}
 * (provider {@code "google-business"}) — {@code secrets.accessToken} / {@code secrets.refreshToken}
 * (used by {@code GbpApiClient}) and {@code config.locationName} (the GBP location the reviews are
 * read from). These properties carry only what is the same for every tenant (global defaults and
 * timeouts).
 *
 * <h2>No-live-Google boundary (§7 hard line)</h2>
 * {@link #apiBaseUrl} defaults to a non-routable {@code .invalid} placeholder host — it is
 * <strong>never a real Google URL</strong>. Per-tenant base URL
 * ({@code IntegrationConnection.config["apiBaseUrl"]}) takes precedence in every production and test
 * path; in every test/CI run the configurable URL is pointed at WireMock
 * ({@code kmosf.gbp.api-base-url=<wireMock.baseUrl()>}). No code path ever hardcodes a real GBP
 * host; tests use sandbox-shaped fake OAuth tokens. The poller is additionally
 * <strong>default-OFF</strong> ({@code kmosf.modules.gbp-reviews.enabled=false}) so no live Google
 * fetch occurs in any default run. Wiring live Google OAuth / GBP API access is a separate,
 * Google-approval-gated human action — NOT authorized by the implementation loop.
 *
 * <h2>GBP-payload-format ASSUMPTION (the adapter boundary)</h2>
 * The exact GBP review JSON shape (the {@code reviews} list, {@code starRating}, {@code comment},
 * {@code reviewer.displayName}, {@code createTime}, and the reply {@code PUT .../reply} body) is
 * assumed in {@code GbpApiClient} <strong>only</strong>. Correcting against the real Google
 * Business Profile API (My Business / Business Information API) is a change to that one client's
 * fetch/post methods — no other file assumes the wire format.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.gbp")
public class GbpProperties {

    /**
     * Global GBP API base URL. Defaults to a non-routable {@code .invalid} placeholder —
     * <strong>never a real Google URL</strong> (§7 hard no-live-Google line). In every test/CI run
     * this is overridden to the WireMock base URL via {@code @DynamicPropertySource}. Per-tenant
     * {@code IntegrationConnection.config["apiBaseUrl"]} takes precedence over this global default
     * in {@code GbpApiClient}.
     */
    private String apiBaseUrl = "https://gbp.googleapis.invalid";

    /** Per-request HTTP timeout (seconds). */
    private long requestTimeoutSeconds = 10;
}
