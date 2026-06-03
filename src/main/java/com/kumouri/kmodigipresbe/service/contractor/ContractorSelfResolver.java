package com.kumouri.kmodigipresbe.service.contractor;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Resolves the calling contractor's own identity from the request context (Phase J — J2).
 *
 * <p>The single chokepoint that every {@code /me/contractor/**} endpoint funnels through —
 * the contractor analogue of {@link com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver}.
 * It asserts the caller IS a contractor and returns the self {@code (tenantId, userId)}
 * from the JWT-derived {@link TenantContext}.
 *
 * <p><strong>The self user id is NEVER taken from the request</strong> (path, query, or
 * body) — only from the verified token. This is what makes "show me MY data" un-spoofable:
 * a contractor cannot pass another user's id to read their time/expenses.
 *
 * <p>Error codes: {@code 4130} caller is not a contractor (403); {@code 4131} token carries
 * no user id (403).
 *
 * <p>Stateless {@code @Component} — holds no client, pool, or scheduler (no new
 * resource-owning bean; the {@code PortalOwnershipGuard} lifecycle posture).
 */
@Component
public class ContractorSelfResolver {

    /** Marks a contractor on the staff chain (a contractor is {@code roles={STAFF,CONTRACTOR}}). */
    public static final String CONTRACTOR_ROLE = "CONTRACTOR";

    /**
     * Resolves and returns the calling contractor's self identity.
     *
     * @return a {@link ContractorSelf} carrying the tenant id and the caller's own user id
     */
    public Mono<ContractorSelf> resolve() {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (!ctx.hasRole(CONTRACTOR_ROLE)) {
                return Mono.error(new DigiPresBeException(
                        "Caller is not a contractor", 4130, 403));
            }
            if (ctx.userId() == null) {
                return Mono.error(new DigiPresBeException(
                        "No user id in token", 4131, 403));
            }
            return Mono.just(new ContractorSelf(ctx.tenantId(), ctx.userId()));
        });
    }

    /**
     * Convenience for callers that only need the self user id. Resolves identity then
     * projects {@link ContractorSelf#userId()}.
     */
    public Mono<UUID> resolveUserId() {
        return resolve().map(ContractorSelf::userId);
    }

    /** The resolved contractor identity (tenant + self user id), both from the verified token. */
    public record ContractorSelf(UUID tenantId, UUID userId) {}
}
