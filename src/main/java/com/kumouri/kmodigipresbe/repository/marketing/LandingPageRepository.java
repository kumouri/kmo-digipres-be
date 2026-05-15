package com.kumouri.kmodigipresbe.repository.marketing;

import com.kumouri.kmodigipresbe.model.marketing.LandingPage;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LandingPageRepository
        extends TenantScopedReactiveMongoRepository<LandingPage, UUID> {

    Mono<LandingPage> findByTenantIdAndSlug(UUID tenantId, String slug);
}
