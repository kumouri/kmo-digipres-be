package com.kumouri.kmodigipresbe.repository.auth;

import com.kumouri.kmodigipresbe.model.auth.WebAuthnChallenge;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WebAuthnChallengeRepository
        extends TenantScopedReactiveMongoRepository<WebAuthnChallenge, UUID> {

    Mono<WebAuthnChallenge> findByTenantIdAndTicket(UUID tenantId, String ticket);

    Mono<Void> deleteByTenantIdAndTicket(UUID tenantId, String ticket);
}
