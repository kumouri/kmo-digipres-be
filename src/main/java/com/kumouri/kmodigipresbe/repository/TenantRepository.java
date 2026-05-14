package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantRepository extends ReactiveMongoRepository<Tenant, UUID> {
    Mono<Tenant> findBySlug(String slug);
}
