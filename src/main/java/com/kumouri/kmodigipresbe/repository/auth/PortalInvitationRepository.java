package com.kumouri.kmodigipresbe.repository.auth;

import com.kumouri.kmodigipresbe.model.auth.PortalInvitation;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PortalInvitationRepository
        extends TenantScopedReactiveMongoRepository<PortalInvitation, UUID> {

    Mono<PortalInvitation> findByTenantIdAndTokenHash(UUID tenantId, String tokenHash);

    Flux<PortalInvitation> findAllByTenantIdAndEmailAndStatus(
            UUID tenantId, String email, PortalInvitation.Status status);
}
