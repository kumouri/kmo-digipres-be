package com.kumouri.kmodigipresbe.module.restaurantlight.repository;

import com.kumouri.kmodigipresbe.module.restaurantlight.model.CateringOrder;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.CateringOrderStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CateringOrderRepository
        extends TenantScopedReactiveMongoRepository<CateringOrder, UUID> {

    Flux<CateringOrder> findAllByTenantIdAndStatus(UUID tenantId, CateringOrderStatus status);

    Flux<CateringOrder> findAllByTenantIdAndContactId(UUID tenantId, UUID contactId);
}
