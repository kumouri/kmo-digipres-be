package com.kumouri.kmodigipresbe.module.restaurantlight.repository;

import com.kumouri.kmodigipresbe.module.restaurantlight.model.Reservation;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.ReservationStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface ReservationRepository
        extends TenantScopedReactiveMongoRepository<Reservation, UUID> {

    Flux<Reservation> findAllByTenantIdAndStatus(UUID tenantId, ReservationStatus status);

    Flux<Reservation> findAllByTenantIdAndReservedAtBetween(UUID tenantId, Instant from, Instant to);
}
