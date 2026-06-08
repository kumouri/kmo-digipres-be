package com.kumouri.kmodigipresbe.module.chairfill.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Repository for the CF-3 {@link WaitlistEntry} pool.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the
 * {@code GapFillService} runs OUTSIDE a request context (it establishes a synthetic
 * {@code TenantContext} per {@code BOOKING_CANCELLED} event).
 */
public interface WaitlistEntryRepository
        extends TenantScopedReactiveMongoRepository<WaitlistEntry, UUID> {

    /** All entries for a tenant in a given status — the gap-fill loads {@code OPEN} ones to rank. */
    Flux<WaitlistEntry> findByTenantIdAndStatus(UUID tenantId, WaitlistEntry.Status status);

    /**
     * Entries for a tenant in a given status, newest join first — the CF-5a waitlist-board read
     * ({@code WaitlistBoardController}) loads the {@code OPEN} ones for the board. Additive (the
     * gap-fill keeps using the unordered finder above).
     */
    Flux<WaitlistEntry> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, WaitlistEntry.Status status);
}
