package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface StaffMemberRepository extends TenantScopedReactiveMongoRepository<StaffMember, UUID> {

    Flux<StaffMember> findByTenantIdAndActive(UUID tenantId, boolean active);
}
