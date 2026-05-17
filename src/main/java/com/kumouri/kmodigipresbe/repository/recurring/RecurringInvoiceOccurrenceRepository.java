package com.kumouri.kmodigipresbe.repository.recurring;

import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoiceOccurrence;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the recurring-invoice idempotency ledger (E-D2/E-D3).
 *
 * <p>{@link #findByTenantIdAndRecurringInvoiceIdAndPeriodKey} is the
 * <strong>explicit-boolean probe</strong> used by the spawn flow: it is mapped to
 * a boolean ({@code .map(e -> true).defaultIfEmpty(false)}) and branched on — it is
 * NEVER used as {@code switchIfEmpty(doSpawn)} (the {@code IdempotencyWebFilter}
 * documented trap; §9 item 3). The unique {@code tenant_recurring_period_idx} is
 * the hard backstop that closes the boolean's read-write race window under Quartz
 * re-fire/misfire/restart.
 */
public interface RecurringInvoiceOccurrenceRepository
        extends TenantScopedReactiveMongoRepository<RecurringInvoiceOccurrence, UUID> {

    Mono<RecurringInvoiceOccurrence> findByTenantIdAndRecurringInvoiceIdAndPeriodKey(
            UUID tenantId, UUID recurringInvoiceId, String periodKey);

    Flux<RecurringInvoiceOccurrence> findAllByTenantIdAndRecurringInvoiceId(
            UUID tenantId, UUID recurringInvoiceId);
}
