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
}
