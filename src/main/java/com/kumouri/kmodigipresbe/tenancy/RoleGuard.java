package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import reactor.core.publisher.Mono;

/**
 * Reactor-friendly role assertion. Reads the current {@link TenantContext} from the
 * Reactor context (failing fast with a 403 if absent) and checks {@code roles}.
 *
 * <p>The codebase doesn't use {@code @PreAuthorize} on controllers (no method-level
 * security is configured); admin-gated handlers compose this guard at the top of
 * their reactive chain via {@code .thenMany(...)} / {@code .then(...)}.
 *
 * <p>Error codes: {@code 1800} (role missing). Phase 9's reserved audit/compliance
 * range is {@code 1800-1899} — the §8 plan originally specified {@code 1400-1499}
 * but Phase 1 {@code DealCrudService} already uses {@code 1400}/{@code 1401}, so
 * the range was shifted to avoid renumbering shipped code.
 */
public final class RoleGuard {

    private RoleGuard() {
    }

    /**
     * @return a {@code Mono<Void>} that completes empty when the current tenant
     * context carries {@code role}, or errors with a {@link DigiPresBeException}
     * (errorCode {@code 1800}, status 403) when it does not.
     */
    public static Mono<Void> requireRole(String role) {
        return TenantContextHolder.required()
                .flatMap(ctx -> ctx.hasRole(role)
                        ? Mono.<Void>empty()
                        : Mono.error(new DigiPresBeException(
                                "Role '" + role + "' required", 1800, 403)));
    }

    /**
     * Inverse of {@link #requireRole(String)} (Phase J — J2). Completes empty when the
     * current tenant context does NOT carry {@code role}, or errors with a
     * {@link DigiPresBeException} (errorCode {@code 4135}, status 403) when it does.
     *
     * <p>Composed at the head of the broad staff LIST/read endpoints so a CONTRACTOR token
     * (which also carries {@code STAFF}, keeping it on the staff security chain) is pushed
     * off them onto the scoped {@code /me/contractor/**} surface. Plain {@code STAFF}
     * (non-contractor) employees are unaffected.
     */
    public static Mono<Void> denyRole(String role) {
        return TenantContextHolder.required()
                .flatMap(ctx -> ctx.hasRole(role)
                        ? Mono.error(new DigiPresBeException(
                                "Role '" + role + "' is not permitted here", 4135, 403))
                        : Mono.<Void>empty());
    }
}
