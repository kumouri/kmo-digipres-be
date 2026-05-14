package com.kumouri.kmodigipresbe.module.homeservices.repository;

import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ServiceAgreementRepository
        extends TenantScopedReactiveMongoRepository<ServiceAgreement, UUID> {

    Flux<ServiceAgreement> findAllByTenantIdAndStatus(UUID tenantId, ServiceAgreementStatus status);

    Flux<ServiceAgreement> findAllByTenantIdAndContactId(UUID tenantId, UUID contactId);

    /**
     * Scheduler scans this across tenants — the {@code @Query} bypasses the
     * tenant-filter wired into {@code TenantScopedSimpleReactiveMongoRepository}
     * so the materializer can run without a per-tenant outer context (the
     * synthetic context is established per-row inside the service).
     */
    @Query("{ 'status': 'ACTIVE' }")
    Flux<ServiceAgreement> findAllActiveAcrossTenants();
}
