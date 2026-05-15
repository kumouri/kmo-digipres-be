package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.compliance.DataSubjectRequest;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DataSubjectRequestRepository
        extends TenantScopedReactiveMongoRepository<DataSubjectRequest, UUID> {

    Mono<DataSubjectRequest> findFirstByTenantIdAndContactIdAndStatusIn(
            UUID tenantId, UUID contactId,
            java.util.Collection<DataSubjectRequest.DsrStatus> statuses);
}
