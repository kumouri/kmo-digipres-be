package com.kumouri.kmodigipresbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration properties for the KMOSF authentication mode.
 *
 * <h2>Auth-mode switch (Phase A)</h2>
 * <ul>
 *   <li>{@code local} (default) — HS256 self-issued JWTs signed with
 *       {@code kmosf.jwt.secret}. This is the current production mode.</li>
 *   <li>{@code zitadel} — RSA/EC JWTs from a Zitadel JWKS endpoint.
 *       Requires {@code kmosf.auth.zitadel.jwks-uri} to be set. Phase A2 makes
 *       this mode usable end-to-end: Zitadel org-claim → tenant resolution,
 *       project-roles-claim → role mapping, JIT user provisioning, and
 *       {@code /auth/login} → 410.</li>
 * </ul>
 *
 * <h2>A/A2 boundary</h2>
 * Phase A shipped the property switch + the profile-conditional decoder; the app
 * boots with zero Zitadel config ({@code local} is the default). Phase A2 adds the
 * Zitadel claim mapping below ({@code roleMap} + claim-name overrides) and the
 * resolver/JIT/login behaviour. Server-side Spring {@code oauth2Login} Zitadel
 * federation for the portal is <strong>Phase H</strong> — out of A2 scope.
 */
@ConfigurationProperties(prefix = "kmosf.auth")
public record AuthModeProperties(
        String mode,
        Zitadel zitadel) {

    /**
     * Nested Zitadel configuration. Only used when {@code kmosf.auth.mode=zitadel}.
     *
     * @param issuerUri  the Zitadel issuer URI (for {@code /auth/discovery} reporting
     *                   and the derived {@code authorizeUrl})
     * @param jwksUri    the Zitadel JWKS endpoint URI; must be non-blank in zitadel mode
     * @param orgClaim   the JWT claim carrying the Zitadel organization id. Default
     *                   {@code urn:zitadel:iam:org:id}.
     * @param rolesClaim the JWT claim carrying the Zitadel project roles. Zitadel emits
     *                   this as a JSON <em>object</em> keyed by role
     *                   ({@code {"staff":{"orgId":"orgName"}}}) — the mapper parses the
     *                   key set, not a string list. Default
     *                   {@code urn:zitadel:iam:org:project:roles}.
     * @param emailClaim the JWT claim carrying the user email (JIT provisioning lookup
     *                   key). Default {@code email}.
     * @param roleMap    Zitadel-role-key → internal-role mapping. Keys are matched
     *                   case-insensitively; unmapped Zitadel roles are ignored. Default
     *                   {@code {admin→ADMIN, staff→STAFF, client→CLIENT}}. An empty
     *                   mapped result makes the resolver raise {@code 3302}.
     */
    public record Zitadel(
            String issuerUri,
            String jwksUri,
            String orgClaim,
            String rolesClaim,
            String emailClaim,
            Map<String, String> roleMap) {

        public static final String DEFAULT_ORG_CLAIM = "urn:zitadel:iam:org:id";
        public static final String DEFAULT_ROLES_CLAIM = "urn:zitadel:iam:org:project:roles";
        public static final String DEFAULT_EMAIL_CLAIM = "email";
        public static final Map<String, String> DEFAULT_ROLE_MAP =
                Map.of("admin", "ADMIN", "staff", "STAFF", "client", "CLIENT");

        public Zitadel {
            if (issuerUri == null) issuerUri = "";
            if (jwksUri == null) jwksUri = "";
            if (orgClaim == null || orgClaim.isBlank()) orgClaim = DEFAULT_ORG_CLAIM;
            if (rolesClaim == null || rolesClaim.isBlank()) rolesClaim = DEFAULT_ROLES_CLAIM;
            if (emailClaim == null || emailClaim.isBlank()) emailClaim = DEFAULT_EMAIL_CLAIM;
            if (roleMap == null || roleMap.isEmpty()) roleMap = DEFAULT_ROLE_MAP;
        }
    }

    public AuthModeProperties {
        if (mode == null || mode.isBlank()) mode = "local";
        if (zitadel == null) zitadel = new Zitadel("", "", null, null, null, null);
    }

    public boolean isLocalMode() {
        return "local".equalsIgnoreCase(mode);
    }

    public boolean isZitadelMode() {
        return "zitadel".equalsIgnoreCase(mode);
    }
}
