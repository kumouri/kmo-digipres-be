package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.config.AuthModeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a Zitadel project-roles JWT claim to the internal role set
 * ({@code STAFF}/{@code ADMIN}/{@code CLIENT}) — Phase A2.
 *
 * <p><strong>Claim shape.</strong> Zitadel emits {@code urn:zitadel:iam:org:project:roles}
 * as a JSON <em>object</em> keyed by role, e.g.
 * <pre>{@code {"staff":{"<orgId>":"<orgDomain>"},"admin":{"<orgId>":"<orgDomain>"}}}</pre>
 * It is <em>not</em> a string array, so {@code jwt.getClaimAsStringList(...)} returns
 * {@code null} (→ silent no-role → a misleading 401). This mapper reads the claim as a
 * {@code Map} and maps its <em>key set</em>.
 *
 * <p>Mapping is config-driven via {@code kmosf.auth.zitadel.role-map} (default
 * {@code admin→ADMIN, staff→STAFF, client→CLIENT}); keys are matched
 * case-insensitively and unmapped Zitadel roles are ignored. An empty result is
 * returned as an empty set — the caller ({@link ZitadelClaimTenantResolver}) raises
 * {@code 3302} (no mappable role).
 */
@Component
@RequiredArgsConstructor
public class RoleClaimMapper {

    private final AuthModeProperties authModeProperties;

    /**
     * Extracts and maps the Zitadel roles claim from {@code jwt}. Never null; an
     * empty set signals "no mappable role" to the caller.
     */
    public Set<String> mapRoles(Jwt jwt) {
        AuthModeProperties.Zitadel cfg = authModeProperties.zitadel();
        Object raw = jwt.getClaim(cfg.rolesClaim());
        if (!(raw instanceof Map<?, ?> roleObject) || roleObject.isEmpty()) {
            return Set.of();
        }
        Map<String, String> roleMap = cfg.roleMap();
        // Case-insensitive lookup over the configured map.
        Map<String, String> lowerKeyed = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : roleMap.entrySet()) {
            lowerKeyed.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        Set<String> mapped = new LinkedHashSet<>();
        for (Object key : roleObject.keySet()) {
            if (key == null) continue;
            String internal = lowerKeyed.get(key.toString().toLowerCase(Locale.ROOT));
            if (internal != null) {
                mapped.add(internal);
            }
        }
        return mapped;
    }
}
