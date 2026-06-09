package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * "Get Paid" AR-4 — reactive repository for {@link PromiseToPay} records.
 *
 * <p>Like every other derived-finder repository in this codebase, the explicit {@code tenantId}
 * argument is REQUIRED — the {@code TenantScopedReactiveMongoRepository} marker does NOT
 * auto-scope derived finders (the {@code PortalInvoicesController} / {@code InvoiceRepository}
 * Javadoc warning).
 *
 * <p>Component-scanned by {@code @EnableReactiveMongoRepositories} — always present (harmless
 * while the AR module is off), exactly like {@link DunningLogRepository}.
 */
public interface PromiseToPayRepository
        extends TenantScopedReactiveMongoRepository<PromiseToPay, UUID> {

    /**
     * Look up all promises for a given invoice with a specific status — the primary query the
     * suppression guard uses: {@code status = ACTIVE} to find the in-effect promise (if any).
     */
    Flux<PromiseToPay> findAllByTenantIdAndInvoiceIdAndStatus(
            UUID tenantId, UUID invoiceId, PromiseToPay.Status status);

    /**
     * List all promises for a given invoice in any status, newest first — the "list by invoice"
     * endpoint query.
     */
    Flux<PromiseToPay> findAllByTenantIdAndInvoiceIdOrderByCreatedAtDesc(
            UUID tenantId, UUID invoiceId);

    /**
     * Tenant-scoped single-entity lookup used by the AR-4 read controller when correlating a
     * promise back to the invoice-not-found validation.
     */
    Mono<PromiseToPay> findByTenantIdAndId(UUID tenantId, UUID id);
}
