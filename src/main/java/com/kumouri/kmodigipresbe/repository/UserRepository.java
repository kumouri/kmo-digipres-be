package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository extends TenantScopedReactiveMongoRepository<User, UUID> {

    /**
     * Login lookup. Email is globally unique on the User collection (see the index),
     * so this query is safe to run without a tenant predicate — it's the way the
     * login flow discovers which tenant a user belongs to before issuing a JWT.
     */
    Mono<User> findByEmail(String email);
}
