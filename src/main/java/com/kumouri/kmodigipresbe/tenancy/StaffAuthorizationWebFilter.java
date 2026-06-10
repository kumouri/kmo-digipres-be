package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Central default-deny authorization for the staff security chain (security fix
 * BE-02 + BE-04).
 *
 * <p>Historically the staff chain authorized with only {@code .anyExchange().authenticated()}
 * (see {@code SecurityConfig}) and relied on each handler <em>remembering</em> to compose a
 * manual {@link RoleGuard}. Many sensitive endpoints were written without that guard
 * (broken function-level authorization, BE-02), and a portal {@code CLIENT} token — minted
 * with the same signing key/decoder as a staff token — was accepted on the staff chain
 * because the {@code portal} claim was never checked (boundary collapse, BE-04). This one
 * filter closes both at the chain level, so authorization is the <em>baseline</em> rather
 * than opt-in. Existing {@code RoleGuard.requireRole}/{@code denyRole} calls are kept intact
 * (defense-in-depth).
 *
 * <h2>Behavior (staff-chain requests only)</h2>
 * <ol>
 *   <li><strong>Reject {@code portal=CLIENT} tokens</strong> → 403 ({@code 1802}). A portal
 *       client must use the {@code /portal/**} chain; presenting its bearer token to a staff
 *       endpoint is denied (BE-04).</li>
 *   <li><strong>Baseline: require role {@code STAFF}</strong> → else 403 ({@code 1803}).
 *       CONTRACTOR / ADMIN users always also carry {@code STAFF} (see
 *       {@code TeamService.sanitizeRoles} — "CONTRACTOR / ADMIN ride the staff security
 *       chain — keep STAFF present"), so they pass; portal {@code CLIENT}s do not (BE-02).</li>
 *   <li><strong>Require role {@code ADMIN}</strong> for paths under {@code /admin/**} and for
 *       {@code /integrations/connections/**} → else 403 ({@code 1804}). This ADMIN-gates the
 *       portal-invitation issuer (BE-01) and the integration-secret endpoints (BE-03).</li>
 * </ol>
 *
 * <h2>Excluded paths</h2>
 * The filter never applies to the public / permitAll surface, mirroring {@code SecurityConfig}'s
 * {@code permitAll} matchers plus the portal chain it never sees: {@code /portal/**},
 * {@code /public/**}, {@code /auth/login}, {@code /auth/health}, {@code /auth/discovery},
 * {@code POST /tenants}, {@code /openapi}, {@code /v3/api-docs/**}, {@code /swagger-ui/**},
 * {@code /webjars/**}, {@code /actuator/health/**}. (The portal chain claims {@code /portal/**}
 * at a higher precedence, so those requests never reach this filter; they are excluded here
 * as belt-and-suspenders.) An unauthenticated request to a non-excluded path carries no
 * {@code SecurityContext}, so the Spring Security chain already rejected it with 401 before
 * this filter's body runs; this filter only constrains <em>authenticated</em> staff-chain
 * requests.
 *
 * <h2>Ordering</h2>
 * Runs at {@link SecurityProperties#DEFAULT_FILTER_ORDER} {@code + 2} — strictly after
 * {@link TenantWebFilter} ({@code +1}, which populates the {@link TenantContext} this reads
 * for the role checks) and before the controller handlers, so a denied request is rejected
 * up front. (The {@code IdempotencyWebFilter} / {@code AccessAuditWebFilter} run at the same
 * or a later order; an authorization denial here short-circuits the chain regardless.)
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 2)
public class StaffAuthorizationWebFilter implements WebFilter {

    static final String ROLE_STAFF = "STAFF";
    static final String ROLE_ADMIN = "ADMIN";

    /** Mirrors {@code JwtTenantResolver.CLAIM_PORTAL}; {@code CLIENT} = portal token. */
    static final String PORTAL_CLAIM_CLIENT = "CLIENT";

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /**
     * permitAll / public surface that must bypass the staff-authorization baseline. Mirrors
     * the {@code permitAll} matchers in {@code SecurityConfig} plus the {@code /portal/**}
     * chain (claimed at higher precedence) so this filter is a pure additive gate.
     */
    private static final List<PathPattern> EXCLUDED = List.of(
            PARSER.parse("/portal/**"),
            PARSER.parse("/public/**"),
            PARSER.parse("/auth/login"),
            PARSER.parse("/auth/health"),
            PARSER.parse("/auth/discovery"),
            PARSER.parse("/tenants"),
            PARSER.parse("/openapi"),
            PARSER.parse("/v3/api-docs/**"),
            PARSER.parse("/swagger-ui/**"),
            PARSER.parse("/webjars/**"),
            PARSER.parse("/actuator/health/**"));

    /** Paths requiring the {@code ADMIN} role on top of the staff baseline. */
    private static final List<PathPattern> ADMIN_REQUIRED = List.of(
            PARSER.parse("/admin/**"),
            PARSER.parse("/integrations/connections/**"));

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (matchesAny(EXCLUDED, path)) {
            return chain.filter(exchange);
        }
        boolean adminRequired = matchesAny(ADMIN_REQUIRED, path);

        // Compute the authorization decision as a Mono<DigiPresBeException> that EMITS the
        // rejection when denied and is EMPTY when the request is allowed. Crucially,
        // computeRejection() NEVER invokes chain.filter — so the .switchIfEmpty below runs
        // chain.filter EXACTLY ONCE (only on the allow path). Naively threading
        // chain.filter() inside a flatMap whose Mono<Void> completes empty would let
        // .switchIfEmpty re-run the chain after the response is already committed
        // ("Error occurred after response was completed") — the exact trap TenantWebFilter
        // documents.
        return computeRejection(adminRequired)
                .flatMap(rejection -> Mono.<Void>error(rejection))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }

    /**
     * Resolves the authorization rejection (if any) for an authenticated staff-chain
     * request. Emits a {@link DigiPresBeException} to deny, or completes EMPTY to allow.
     *
     * <ul>
     *   <li>No {@code SecurityContext} → empty (allow this filter; the request is either
     *       already 401'd by the security chain for a protected path, or is an unauthenticated
     *       hit the chain handles — this filter never invents auth).</li>
     *   <li>{@code portal=CLIENT} → 1802 (BE-04).</li>
     *   <li>missing {@code STAFF} baseline → 1803 (BE-02).</li>
     *   <li>admin-gated path missing {@code ADMIN} → 1804 (BE-02).</li>
     * </ul>
     */
    private Mono<DigiPresBeException> computeRejection(boolean adminRequired) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(secCtx -> secCtx.getAuthentication())
                .flatMap(authentication -> {
                    // BE-04: reject portal CLIENT tokens straight off the JWT principal
                    // (the portal claim is not carried into TenantContext).
                    if (authentication.getPrincipal() instanceof Jwt jwt
                            && PORTAL_CLAIM_CLIENT.equals(jwt.getClaimAsString(
                                    JwtTenantResolver.CLAIM_PORTAL))) {
                        return Mono.just(new DigiPresBeException(
                                "Portal client tokens are not accepted on the staff API",
                                1802, 403));
                    }
                    // BE-02: STAFF baseline + ADMIN for admin-gated paths, from TenantContext.
                    return TenantContextHolder.required().handle((ctx, sink) -> {
                        if (!ctx.hasRole(ROLE_STAFF)) {
                            sink.next(new DigiPresBeException(
                                    "Role '" + ROLE_STAFF + "' required", 1803, 403));
                        } else if (adminRequired && !ctx.hasRole(ROLE_ADMIN)) {
                            sink.next(new DigiPresBeException(
                                    "Role '" + ROLE_ADMIN + "' required", 1804, 403));
                        }
                        // else: complete empty → allowed
                    });
                });
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
