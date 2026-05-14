package com.kumouri.kmodigipresbe.repository.auth;

import com.kumouri.kmodigipresbe.model.auth.MagicLinkToken;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MagicLinkTokenRepository
        extends TenantScopedReactiveMongoRepository<MagicLinkToken, UUID> {

    Mono<MagicLinkToken> findByTenantIdAndTokenHash(UUID tenantId, String tokenHash);
}
