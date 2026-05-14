package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtTenantResolver implements TenantResolver {

    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";

    @Override
    public Mono<TenantContext> resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.empty();
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Mono.empty();
        }
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
