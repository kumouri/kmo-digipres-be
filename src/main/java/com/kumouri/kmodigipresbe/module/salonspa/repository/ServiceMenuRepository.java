package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ServiceMenuRepository extends TenantScopedReactiveMongoRepository<ServiceMenu, UUID> {

    /**
     * All service menus for a tenant. Used by the ChairFill (CF-1)
     * {@code NoShowRiskScoringService} to resolve {@code ServiceMenuItem.price} for the
     * price-band feature. Explicit-param derived query: bypasses the auto-tenant-filter
     * so it can run outside a request {@code TenantContext} (the nightly job has none).
     */
    Flux<ServiceMenu> findAllByTenantId(UUID tenantId);
}
