package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for {@link Quote}.
 *
 * <p>Portal contact/company finders (Phase G — G-D9) mirror the
 * {@code InvoiceRepository} portal-finder precedent. Derived finders require an
 * explicit {@code tenantId} argument — the
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders (per the
 * {@code PortalInvoicesController} Javadoc).
 */
public interface QuoteRepository extends TenantScopedReactiveMongoRepository<Quote, UUID> {

    Flux<Quote> findAllByTenantIdAndDealId(UUID tenantId, UUID dealId);

    Flux<Quote> findAllByTenantIdAndContactId(UUID tenantId, UUID contactId);

    /**
     * Tenant-scoped single-entity lookup used by {@code PortalOwnershipGuard}
     * (Phase G — G-D2). The explicit {@code tenantId} predicate blocks cross-tenant
     * access at the query level; the ownership predicate in the guard then blocks
     * cross-contact access.
     */
    Mono<Quote> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Portal "my quotes" when the caller's contact has no company link — selects
     * only the direct contactId match. Avoids the {@code {companyId: null}}
     * predicate trap in the {@code $or}-based query below.
     */
    Flux<Quote> findAllByTenantIdAndContactIdOrderByCreatedAtDesc(UUID tenantId, UUID contactId);

    /**
     * Portal "my quotes" when the caller's contact has a companyId — quotes match
     * if either the contactId or companyId points at them. Mirrors
     * {@code InvoiceRepository.findAllByTenantAndContactOrCompany}.
     */
    @Query(value = "{ 'tenantId': ?0, '$or': [ { 'contactId': ?1 }, { 'companyId': ?2 } ] }",
            sort = "{ 'createdAt': -1 }")
    Flux<Quote> findAllByTenantAndContactOrCompany(UUID tenantId, UUID contactId, UUID companyId);
}
