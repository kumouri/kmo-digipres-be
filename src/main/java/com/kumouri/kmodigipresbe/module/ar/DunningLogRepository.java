package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the {@link DunningLog} idempotency ledger ("Get Paid" AR / collections, band
 * 4600-4619).
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders. The probe
 * is used as an <strong>explicit-boolean</strong> seen/not-seen branch in {@link ArAgingSweepJob}
 * (never {@code switchIfEmpty(create)}); the unique {@code tenant_invoice_tier_idx} backstops the
 * ledger-insert-FIRST against a concurrent re-run.
 */
public interface DunningLogRepository
        extends TenantScopedReactiveMongoRepository<DunningLog, UUID> {

    Mono<DunningLog> findByTenantIdAndInvoiceIdAndTier(
            UUID tenantId, UUID invoiceId, DunningLog.DunningTier tier);
}
