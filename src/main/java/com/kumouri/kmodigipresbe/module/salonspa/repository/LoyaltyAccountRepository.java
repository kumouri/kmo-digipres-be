package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyAccount;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LoyaltyAccountRepository extends TenantScopedReactiveMongoRepository<LoyaltyAccount, UUID> {

    Mono<LoyaltyAccount> findByTenantIdAndContactId(UUID tenantId, UUID contactId);
}
