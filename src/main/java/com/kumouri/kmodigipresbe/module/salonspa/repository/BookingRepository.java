package com.kumouri.kmodigipresbe.module.salonspa.repository;

import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface BookingRepository extends TenantScopedReactiveMongoRepository<Booking, UUID> {

    Flux<Booking> findByTenantIdAndContactId(UUID tenantId, UUID contactId);

    Flux<Booking> findByTenantIdAndStaffMemberIdAndScheduledStartBetween(
            UUID tenantId, UUID staffMemberId, Instant from, Instant to);

    Flux<Booking> findByTenantIdAndStatus(UUID tenantId, BookingStatus status);

    /** Finds the most-recent completed booking for a contact — used by the rebook nudge. */
    Flux<Booking> findByTenantIdAndContactIdAndStatusOrderByScheduledStartDesc(
            UUID tenantId, UUID contactId, BookingStatus status);
}
