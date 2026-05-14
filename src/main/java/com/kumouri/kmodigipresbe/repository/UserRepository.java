package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository extends TenantScopedReactiveMongoRepository<User, UUID> {

    /**
     * Staff login lookup. {@code (email, portal=STAFF)} is unique via a partial-filter
     * index on {@link User}, so this query is safe to run without a tenant predicate —
     * it's how the staff login flow discovers which tenant a user belongs to before
     * issuing a JWT. Filtering by portal=STAFF is what keeps a colliding portal CLIENT
     * email on another tenant from being returned by mistake.
     */
    Mono<User> findByEmailAndPortal(String email, User.Portal portal);

    /**
     * Tenant-scoped email lookup. Used by the portal auth-linking path so a Google
     * sign-in for {@code alice@x.com} on tenant A finds the existing portal User on
     * that tenant (without colliding with a CLIENT also named {@code alice@x.com} on
     * tenant B).
     */
    Mono<User> findByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Phase 9g @mention resolution: given a handle parsed out of an activity note
     * (e.g. {@code @alice}), find a user whose email begins with {@code alice@}.
     * Returns at most one user; if multiple match, the first by mongo order is
     * returned and the others are ignored.
     */
    Mono<User> findFirstByTenantIdAndEmailStartingWith(UUID tenantId, String prefix);
}
