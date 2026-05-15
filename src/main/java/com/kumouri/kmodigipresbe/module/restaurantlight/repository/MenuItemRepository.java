package com.kumouri.kmodigipresbe.module.restaurantlight.repository;

import com.kumouri.kmodigipresbe.module.restaurantlight.model.MenuItem;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.MenuItemCourse;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface MenuItemRepository
        extends TenantScopedReactiveMongoRepository<MenuItem, UUID> {

    Flux<MenuItem> findAllByTenantIdAndAvailable(UUID tenantId, boolean available);

    Flux<MenuItem> findAllByTenantIdAndCourse(UUID tenantId, MenuItemCourse course);
}
