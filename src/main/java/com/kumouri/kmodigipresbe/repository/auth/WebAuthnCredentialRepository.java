package com.kumouri.kmodigipresbe.repository.auth;

import com.kumouri.kmodigipresbe.model.auth.WebAuthnCredential;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WebAuthnCredentialRepository
        extends TenantScopedReactiveMongoRepository<WebAuthnCredential, UUID> {

    Mono<WebAuthnCredential> findByTenantIdAndCredentialIdBase64Url(
            UUID tenantId, String credentialIdBase64Url);

    Flux<WebAuthnCredential> findAllByTenantIdAndUserId(UUID tenantId, UUID userId);
}
