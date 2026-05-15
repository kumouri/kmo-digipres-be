package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface QuoteRepository extends TenantScopedReactiveMongoRepository<Quote, UUID> {
    Flux<Quote> findAllByTenantIdAndDealId(UUID tenantId, UUID dealId);
    Flux<Quote> findAllByTenantIdAndContactId(UUID tenantId, UUID contactId);
}
