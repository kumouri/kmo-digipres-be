package com.kumouri.kmodigipresbe.repository.contract;

import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
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
 *
 * <p>Portal contact/company finders (Phase G — G-D9) mirror the
 * {@code InvoiceRepository} portal-finder precedent. Derived finders require an
 * explicit {@code tenantId} argument — the
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders (per the
 * {@code PortalInvoicesController} Javadoc).
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

    /**
     * Portal "my contracts" when the caller's contact has no company link — selects
     * only the direct contactId match. Avoids the {@code {companyId: null}}
     * predicate trap in the {@code $or}-based query below.
     */
    Flux<Contract> findAllByTenantIdAndContactIdOrderByCreatedAtDesc(
            UUID tenantId, UUID contactId);

    /**
     * Portal "my contracts" when the caller's contact has a companyId — contracts
     * match if either the contactId or companyId points at them. Mirrors
     * {@code InvoiceRepository.findAllByTenantAndContactOrCompany}.
     */
    @Query(value = "{ 'tenantId': ?0, '$or': [ { 'contactId': ?1 }, { 'companyId': ?2 } ] }",
            sort = "{ 'createdAt': -1 }")
    Flux<Contract> findAllByTenantAndContactOrCompany(
            UUID tenantId, UUID contactId, UUID companyId);
}
