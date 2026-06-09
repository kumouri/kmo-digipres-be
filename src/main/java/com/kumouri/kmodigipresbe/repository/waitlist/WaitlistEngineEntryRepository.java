package com.kumouri.kmodigipresbe.repository.waitlist;

import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * E4 — repository for the generic {@link WaitlistEntry} pool.
 *
 * <p>Named {@code WaitlistEngineEntryRepository} (not {@code WaitlistEntryRepository}) deliberately: the
 * shipped ChairFill CF-3 {@code module.chairfill.model.WaitlistEntryRepository} already owns the simple
 * name {@code WaitlistEntryRepository}, and Spring Data derives a repository's bean name from the
 * uncapitalized simple name — two repos with the same simple name collide
 * ({@code BeanDefinitionOverrideException}). A distinct simple name keeps the generic engine additive and
 * chairfill byte-equivalent.
 *
 * <p>Derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders, and the
 * {@code GapFillEngine} runs OUTSIDE a request context (it establishes a synthetic {@code TenantContext}).
 */
public interface WaitlistEngineEntryRepository
        extends TenantScopedReactiveMongoRepository<WaitlistEntry, UUID> {

    /** All entries for a tenant in a given status — the gap-fill loads {@code OPEN} ones to rank. */
    Flux<WaitlistEntry> findByTenantIdAndStatus(UUID tenantId, WaitlistEntry.Status status);

    /** Entries for a tenant in a given status, newest join first — the admin board read. */
    Flux<WaitlistEntry> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, WaitlistEntry.Status status);

    /** All entries for a tenant, newest join first — the admin list. */
    Flux<WaitlistEntry> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** A single tenant-scoped entry by id — the admin get/update/cancel (explicit tenant predicate). */
    Mono<WaitlistEntry> findByTenantIdAndId(UUID tenantId, UUID id);
}
