package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.config.AuthModeProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Resolves a {@link TenantContext} from a Zitadel-issued JWT (Phase A2).
 *
 * <p>This is a plain collaborator, <strong>not</strong> a second {@link TenantResolver}
 * bean — {@link JwtTenantResolver} delegates here only when
 * {@code kmosf.auth.mode=zitadel}. Keeping it off the {@code TenantResolver} type
 * avoids an ambiguous-bean injection into {@code TenantWebFilter}'s constructor.
 *
 * <p>Resolution:
 * <ol>
 *   <li>org claim ({@code urn:zitadel:iam:org:id} by default) → tenant id via
 *       {@link ZitadelOrgTenantCache}. Missing/blank → {@code 3300} (401);
 *       org not federated by any tenant → {@code 3301} (403, raised by the cache).</li>
 *   <li>project-roles claim → internal roles via {@link RoleClaimMapper}. Empty
 *       mapped set → {@code 3302} (403).</li>
 *   <li>JIT user provisioning (Phase A2.3) folds the resolved {@code userId} into the
 *       returned context. Until A2.3 wires it, the context carries a null userId
 *       (tenant + roles still resolve, so audit attribution degrades exactly like the
 *       portal OAuth bootstrap precedent).</li>
 * </ol>
 *
 * <p>Error range {@code 3300-3399} is the Phase A2 runtime range (distinct from the
 * {@code 3200-3299} startup/config range).
 */
@Component
@RequiredArgsConstructor
public class ZitadelClaimTenantResolver {

    private final AuthModeProperties authModeProperties;
    private final ZitadelOrgTenantCache orgTenantCache;
    private final RoleClaimMapper roleClaimMapper;

    public Mono<TenantContext> resolve(Jwt jwt) {
        AuthModeProperties.Zitadel cfg = authModeProperties.zitadel();

        String orgId = jwt.getClaimAsString(cfg.orgClaim());
        if (orgId == null || orgId.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Zitadel token missing organization claim (" + cfg.orgClaim() + ")",
                    3300, 401));
        }

        Set<String> roles = roleClaimMapper.mapRoles(jwt);
        if (roles.isEmpty()) {
            return Mono.error(new DigiPresBeException(
                    "Zitadel token carries no role mappable to a KMOSF role",
                    3302, 403));
        }

        return orgTenantCache.resolve(orgId)
                .map(tenantId -> new TenantContext(tenantId, null, roles));
    }
}
