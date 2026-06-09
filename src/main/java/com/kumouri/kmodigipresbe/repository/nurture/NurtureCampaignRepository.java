package com.kumouri.kmodigipresbe.repository.nurture;

import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link NurtureCampaign} (E1 — Nurture / Cadence Engine).
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders.
 */
public interface NurtureCampaignRepository
        extends TenantScopedReactiveMongoRepository<NurtureCampaign, UUID> {

    Flux<NurtureCampaign> findAllByTenantId(UUID tenantId);

    Mono<NurtureCampaign> findByTenantIdAndId(UUID tenantId, UUID id);
}
