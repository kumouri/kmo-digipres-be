package com.kumouri.kmodigipresbe.controller.admin;

import com.kumouri.kmodigipresbe.repository.FieldPermissionPolicyRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import com.kumouri.kmodigipresbe.tenancy.permissions.FieldPermissionPolicy;
import com.kumouri.kmodigipresbe.tenancy.permissions.FieldPermissionRule;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only endpoints to view + replace a tenant's
 * {@link FieldPermissionPolicy}. Gated by Phase 9a's {@link RoleGuard}.
 *
 * <p>The policy is upserted in place — there's one row per tenant via the
 * unique index on {@code tenantId}. PUT replaces the rules list wholesale;
 * partial-edit endpoints can land later if the rule set grows.
 */
@RestController
@RequestMapping("/admin/field-permissions")
@RequiredArgsConstructor
public class FieldPermissionsController {

    private final FieldPermissionPolicyRepository policies;

    @GetMapping
    public Mono<FieldPermissionPolicy> get() {
        return RoleGuard.requireRole("ADMIN")
                .then(TenantContextHolder.required())
                .flatMap(ctx -> policies.findByTenantId(ctx.tenantId())
                        .defaultIfEmpty(FieldPermissionPolicy.builder()
                                .tenantId(ctx.tenantId())
                                .rules(List.of())
                                .build()));
    }

    @PutMapping
    public Mono<FieldPermissionPolicy> put(@RequestBody FieldPermissionPolicy body) {
        return RoleGuard.requireRole("ADMIN")
                .then(TenantContextHolder.required())
                .flatMap(ctx -> upsert(ctx.tenantId(), body.getRules()));
    }

    private Mono<FieldPermissionPolicy> upsert(UUID tenantId, List<FieldPermissionRule> rules) {
        return policies.findByTenantId(tenantId)
                .switchIfEmpty(Mono.defer(() -> policies.save(FieldPermissionPolicy.builder()
                        .rules(rules == null ? List.of() : rules)
                        .build())))
                .flatMap(existing -> {
                    existing.setRules(rules == null ? List.of() : rules);
                    return policies.save(existing);
                });
    }
}
