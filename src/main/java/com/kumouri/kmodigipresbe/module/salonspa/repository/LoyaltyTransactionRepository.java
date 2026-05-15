package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyTransaction;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface LoyaltyTransactionRepository extends TenantScopedReactiveMongoRepository<LoyaltyTransaction, UUID> {

    Flux<LoyaltyTransaction> findByTenantIdAndAccountIdOrderByCreatedAtDesc(UUID tenantId, UUID accountId);
}
