package com.kumouri.kmodigipresbe.integration.activepieces;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global Activepieces configuration defaults (Phase H — H.5 / H-D5).
 *
 * <p>Per-tenant Activepieces target URLs and capability tokens come in via the
 * {@code POST /api/v1/integrations/activepieces/seed} admin endpoint — they
 * are NOT stored here.  These properties carry only the global defaults that
 * are the same across all tenants (currently: the timeout).
 *
 * <h2>No-live-Activepieces boundary (§7 hard line)</h2>
 * No host or URL default is hardcoded here.  Each tenant provides its own
 * Activepieces webhook-trigger URL at seed time.  In every test/CI run the
 * controller URL is overridden to the WireMock base URL via
 * {@code @DynamicPropertySource}.  Wiring a live Activepieces deployment or
 * credential is a separate human action — NOT authorized by the implementation
 * loop.
 */
@Data
@ConfigurationProperties(prefix = "kmosf.activepieces")
public class ActivepiecesProperties {

    /**
     * Default per-request HTTP timeout (seconds) for Activepieces webhook
     * delivery.  Delivery is handled by the existing
     * {@link com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService}
     * which has its own 10-second timeout; this field is a placeholder for
     * future per-provider overrides.
     */
    private long requestTimeoutSeconds = 10;
}
