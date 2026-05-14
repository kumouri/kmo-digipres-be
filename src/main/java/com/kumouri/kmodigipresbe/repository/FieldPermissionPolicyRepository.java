package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import com.kumouri.kmodigipresbe.tenancy.permissions.FieldPermissionPolicy;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface FieldPermissionPolicyRepository
        extends TenantScopedReactiveMongoRepository<FieldPermissionPolicy, UUID> {

    Mono<FieldPermissionPolicy> findByTenantId(UUID tenantId);
}
