package com.kumouri.kmodigipresbe.tenancy;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface TenantScopedReactiveMongoRepository<T extends TenantScoped, ID>
        extends ReactiveMongoRepository<T, ID> {
}
