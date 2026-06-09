package com.kumouri.kmodigipresbe.module.proposals;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the {@link SowDraft} prose document (AI Proposal / SOW generator, band 4620-4639).
 *
 * <p>The derived finders carry an explicit {@code tenantId} predicate — the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders (the
 * {@code QuoteRepository} / {@code DunningLogRepository} precedent). {@link #findByTenantIdAndQuoteId}
 * backs the {@code GET /proposals/{id}} read (the prose half of a drafted SOW, looked up by the DRAFT
 * Quote's id within the caller's tenant).
 */
public interface SowDraftRepository
        extends TenantScopedReactiveMongoRepository<SowDraft, UUID> {

    Mono<SowDraft> findByTenantIdAndQuoteId(UUID tenantId, UUID quoteId);
}
