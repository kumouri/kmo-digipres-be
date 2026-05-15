package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface ServiceMenuRepository extends TenantScopedReactiveMongoRepository<ServiceMenu, UUID> {
}
