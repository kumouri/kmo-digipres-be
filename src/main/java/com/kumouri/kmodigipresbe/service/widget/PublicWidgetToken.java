package com.kumouri.kmodigipresbe.service.widget;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims of a public-widget token. Tenants embed signed tokens in the
 * widget snippet they paste onto their website; submissions to {@code /public/widget/**}
 * include the token in the URL path, the chain verifies it via
 * {@link PublicWidgetTokenService}, and downstream handlers establish synthetic
 * tenant context using these claims.
 */
public record PublicWidgetToken(UUID tenantId, String widgetType, Instant expiresAt) {
}
