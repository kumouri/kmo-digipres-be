package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.catalog.PriceList;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface PriceListRepository extends TenantScopedReactiveMongoRepository<PriceList, UUID> {
}
