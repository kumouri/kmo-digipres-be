package com.kumouri.kmodigipresbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the KMOSF authentication mode.
 *
 * <h2>Auth-mode switch (Phase A)</h2>
 * <ul>
 *   <li>{@code local} (default) — HS256 self-issued JWTs signed with
 *       {@code kmosf.jwt.secret}. This is the current production mode.</li>
 *   <li>{@code zitadel} — RSA/EC JWTs from a Zitadel JWKS endpoint.
 *       Requires {@code kmosf.auth.zitadel.jwks-uri} to be set.
 *       In Phase A, Zitadel mode wires the {@code NimbusReactiveJwtDecoder} but
 *       {@code JwtTenantResolver} still reads {@code tid}/{@code uid}/{@code roles}
 *       claims — a Zitadel token without those claims fails with errorCode 1002.
 *       Full Zitadel federation (claim mapping, JIT provisioning) is Phase A2.</li>
 * </ul>
 *
 * <h2>A/A2 boundary</h2>
 * Phase A ships ONLY the property switch and the profile-conditional decoder.
 * The app boots with zero Zitadel config ({@code local} is the default).
 * Phase A2 delivers: Zitadel tenant-claim resolution, role mapping, JIT provisioning,
 * {@code login}→410, portal-chain Zitadel integration.
 */
@ConfigurationProperties(prefix = "kmosf.auth")
public record AuthModeProperties(
        String mode,
        Zitadel zitadel) {

    /**
     * Nested Zitadel configuration. Only used when {@code kmosf.auth.mode=zitadel}.
     *
     * @param issuerUri the Zitadel issuer URI (for {@code /auth/discovery} reporting)
     * @param jwksUri   the Zitadel JWKS endpoint URI; must be non-blank in zitadel mode
     */
    public record Zitadel(String issuerUri, String jwksUri) {
        public Zitadel {
            if (issuerUri == null) issuerUri = "";
            if (jwksUri == null) jwksUri = "";
        }
    }

    public AuthModeProperties {
        if (mode == null || mode.isBlank()) mode = "local";
        if (zitadel == null) zitadel = new Zitadel("", "");
    }

    public boolean isLocalMode() {
        return "local".equalsIgnoreCase(mode);
    }

    public boolean isZitadelMode() {
        return "zitadel".equalsIgnoreCase(mode);
    }
}
