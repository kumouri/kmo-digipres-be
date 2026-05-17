package com.kumouri.kmodigipresbe.repository.contract;

import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link Contract} (Phase F — F-D3).
 *
 * <p>{@link #existsByTenantIdAndQuoteIdAndTemplateId} + {@link
 * #findFirstByTenantIdAndQuoteIdAndTemplateId} are the
 * <strong>explicit-boolean idempotency probe</strong> used by
 * {@code ContractService.spawnFromQuote}: mapped to a boolean then branched
 * ({@code exists ? return existing : doCreate}) — NEVER
 * {@code switchIfEmpty(create)} (the Phase-C {@code convertFromDeal} /
 * Phase-E occurrence-probe discipline).
 *
 * <p>{@link #findByTenantIdAndDocumensoDocumentId} is the webhook correlation
 * key: Documenso returns a {@code documentId} and we look up the CRM
 * {@link Contract} from it (errorCode 3716 if absent, defensive).
 */
public interface ContractRepository
        extends TenantScopedReactiveMongoRepository<Contract, UUID> {

    Mono<Contract> findByTenantIdAndId(UUID tenantId, UUID id);

    Flux<Contract> findAllByTenantId(UUID tenantId);

    /** Webhook correlation key: look up the CRM Contract by its Documenso document id. */
    Mono<Contract> findByTenantIdAndDocumensoDocumentId(UUID tenantId, String documensoDocumentId);

    /**
     * Explicit-boolean idempotency probe for {@code spawnFromQuote}. Returns
     * {@code true} if a Contract already exists for this (tenant, quoteId, templateId)
     * triple — meaning the spawn has already happened and the endpoint should return
     * the existing contract, never create a second one.
     */
    Mono<Boolean> existsByTenantIdAndQuoteIdAndTemplateId(
            UUID tenantId, UUID quoteId, UUID templateId);

    /**
     * Retrieve the existing Contract when {@link #existsByTenantIdAndQuoteIdAndTemplateId}
     * returns {@code true}. The {@code First} suffix is required because the unique
     * constraint is at the service level (the partial-unique index is on
     * {@code contractNumber}, not on {@code quoteId+templateId}); in practice at most
     * one contract exists for this triple.
     */
    Mono<Contract> findFirstByTenantIdAndQuoteIdAndTemplateId(
            UUID tenantId, UUID quoteId, UUID templateId);

    /**
     * All contracts linked to a deal — used to surface contract history on a deal
     * record and by the SOW promotion guard.
     */
    Flux<Contract> findAllByTenantIdAndDealId(UUID tenantId, UUID dealId);
}
