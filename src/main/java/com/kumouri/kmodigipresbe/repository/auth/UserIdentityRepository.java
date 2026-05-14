package com.kumouri.kmodigipresbe.repository.auth;

import com.kumouri.kmodigipresbe.model.auth.UserIdentity;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserIdentityRepository
        extends TenantScopedReactiveMongoRepository<UserIdentity, UUID> {

    Mono<UserIdentity> findByTenantIdAndProviderAndProviderSubject(
            UUID tenantId, UserIdentity.Provider provider, String providerSubject);

    Flux<UserIdentity> findAllByTenantIdAndUserId(UUID tenantId, UUID userId);
}
