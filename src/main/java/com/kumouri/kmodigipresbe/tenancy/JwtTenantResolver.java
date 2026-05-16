package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.config.AuthModeProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single {@link TenantResolver} bean. Dual-mode (Phase A2): one resolver, two
 * strategies selected by {@code kmosf.auth.mode}.
 *
 * <ul>
 *   <li><strong>{@code local}</strong> (default, {@code matchIfMissing=true}) — the
 *       original HS256 self-issued path: read the {@code tid}/{@code uid}/{@code roles}
 *       claims minted by {@code JwtTokenService}. This branch is the pre-A2 code moved
 *       <em>verbatim</em>; the Zitadel collaborators are never touched in local mode,
 *       so local behaviour (and the 333-test IT suite, which runs exclusively in local
 *       mode) is byte-identical to before Phase A2.</li>
 *   <li><strong>{@code zitadel}</strong> — delegate to {@link ZitadelClaimTenantResolver}
 *       (org-claim → tenant, project-roles-claim → roles, JIT user provisioning).</li>
 * </ul>
 *
 * <p>The non-{@code Jwt}/unauthenticated guard is mode-agnostic and shared (both modes
 * require a {@code Jwt} principal; a missing one is "no tenant", i.e. {@code Mono.empty()}
 * — never an error — exactly as before).
 *
 * <p>The {@code CLAIM_*} constants remain public: {@code JwtTokenService} mints with
 * them in local mode.
 */
@Component
@RequiredArgsConstructor
public class JwtTenantResolver implements TenantResolver {

    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PORTAL = "portal";

    private final AuthModeProperties authModeProperties;
    private final ZitadelClaimTenantResolver zitadelClaimTenantResolver;

    @Override
    public Mono<TenantContext> resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.empty();
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Mono.empty();
        }
        if (authModeProperties.isLocalMode()) {
            return resolveLocal(jwt);
        }
        return zitadelClaimTenantResolver.resolve(jwt);
    }

    /**
     * Local HS256 path — moved verbatim from the pre-A2 {@code resolve(...)} body
     * (only the {@code Jwt} is now passed in, since the principal cast happens in the
     * shared guard above). Behaviour is unchanged: parse {@code tid}/{@code uid}/
     * {@code roles}; any parse failure → {@code DigiPresBeException(...,1002,401)}.
     */
    private Mono<TenantContext> resolveLocal(Jwt jwt) {
        try {
            UUID tenantId = UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT_ID));
            String userIdClaim = jwt.getClaimAsString(CLAIM_USER_ID);
            UUID userId = userIdClaim != null ? UUID.fromString(userIdClaim) : null;
            List<String> rolesList = jwt.getClaimAsStringList(CLAIM_ROLES);
            Set<String> roles = rolesList != null ? new HashSet<>(rolesList) : Set.of();
            return Mono.just(new TenantContext(tenantId, userId, roles));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Mono.error(new DigiPresBeException(
                    "JWT missing or invalid tenant claim", 1002, 401));
        }
    }
}
