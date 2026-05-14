package com.kumouri.kmodigipresbe.module.fieldservice.repository;

import com.kumouri.kmodigipresbe.module.fieldservice.model.Capture;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CaptureRepository extends TenantScopedReactiveMongoRepository<Capture, UUID> {

    Flux<Capture> findAllByTenantIdAndWorkOrderIdOrderByCapturedAtDesc(
            UUID tenantId, UUID workOrderId);
}
