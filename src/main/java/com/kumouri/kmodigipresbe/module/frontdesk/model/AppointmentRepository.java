package com.kumouri.kmodigipresbe.module.frontdesk.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-1) — repository for {@link Appointment}, the health analogue of the salon
 * {@code BookingRepository}.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate: the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the nightly
 * no-show scorer runs OUTSIDE a request {@code TenantContext}, so the tenant must be passed explicitly
 * (the {@code BookingRepository} / {@code ListingRepository} posture).
 */
public interface AppointmentRepository extends TenantScopedReactiveMongoRepository<Appointment, UUID> {

    /**
     * All appointments for a tenant. Used by {@code FrontDeskNoShowScoringService} to load the full
     * appointment history once per nightly run (mirrors {@code BookingRepository.findAllByTenantId}).
     */
    Flux<Appointment> findAllByTenantId(UUID tenantId);

    /** All appointments for a contact (scoped to tenant). */
    Flux<Appointment> findByTenantIdAndContactId(UUID tenantId, UUID contactId);

    /**
     * All appointments for a tenant whose {@code scheduledStart} falls in {@code [from, to]}. Backs the
     * {@code GET /frontdesk/risk/appointments} risk-sorted day-view read.
     */
    Flux<Appointment> findByTenantIdAndScheduledStartBetween(UUID tenantId, Instant from, Instant to);

    Flux<Appointment> findByTenantIdAndStatus(UUID tenantId, AppointmentStatus status);

    /** Newest-first list for the tenant — the staff appointment console. */
    Flux<Appointment> findByTenantIdOrderByScheduledStartDesc(UUID tenantId);

    Mono<Appointment> findByIdAndTenantId(UUID id, UUID tenantId);
}
