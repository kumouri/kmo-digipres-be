package com.kumouri.kmodigipresbe.module.fieldservice.repository;

import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface WorkOrderRepository extends TenantScopedReactiveMongoRepository<WorkOrder, UUID> {

    Flux<WorkOrder> findAllByTenantIdAndStatus(UUID tenantId, WorkOrderStatus status);

    Flux<WorkOrder> findAllByTenantIdAndScheduledStartBetween(
            UUID tenantId, Instant from, Instant to);
}
