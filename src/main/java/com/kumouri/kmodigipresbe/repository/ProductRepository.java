package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.catalog.Product;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProductRepository extends TenantScopedReactiveMongoRepository<Product, UUID> {
    Mono<Product> findByTenantIdAndSku(UUID tenantId, String sku);
}
