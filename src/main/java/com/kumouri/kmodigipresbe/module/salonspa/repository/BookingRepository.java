package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface BookingRepository extends TenantScopedReactiveMongoRepository<Booking, UUID> {

    /**
     * All bookings for a tenant. Used by the ChairFill (CF-1)
     * {@code NoShowRiskScoringService} to load the full booking history once per
     * nightly run — mirrors the lead-scorer's {@code ContactRepository.findAllByTenantId}.
     * Explicit-param derived query: bypasses the auto-tenant-filter so it can run
     * outside a request {@code TenantContext} (the nightly job has none).
     */
    Flux<Booking> findAllByTenantId(UUID tenantId);

    Flux<Booking> findByTenantIdAndContactId(UUID tenantId, UUID contactId);

    Flux<Booking> findByTenantIdAndStaffMemberIdAndScheduledStartBetween(
            UUID tenantId, UUID staffMemberId, Instant from, Instant to);

    /**
     * All bookings for a tenant whose {@code scheduledStart} falls in {@code [from, to]}.
     * Backs the ChairFill (CF-1) {@code GET /chairfill/risk/bookings} day-view read.
     */
    Flux<Booking> findByTenantIdAndScheduledStartBetween(UUID tenantId, Instant from, Instant to);

    Flux<Booking> findByTenantIdAndStatus(UUID tenantId, BookingStatus status);

    /** Finds the most-recent completed booking for a contact — used by the rebook nudge. */
    Flux<Booking> findByTenantIdAndContactIdAndStatusOrderByScheduledStartDesc(
            UUID tenantId, UUID contactId, BookingStatus status);
}
