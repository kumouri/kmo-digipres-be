package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface InvoiceRepository extends TenantScopedReactiveMongoRepository<Invoice, UUID> {

    /**
     * Tenant-scoped single-entity lookup used by {@code PortalOwnershipGuard}
     * (Phase G — G-D2). The explicit {@code tenantId} predicate blocks cross-tenant
     * access at the query level; the ownership predicate in the guard then blocks
     * cross-contact access. Derived finders require an explicit {@code tenantId}
     * argument — the {@code TenantScopedReactiveMongoRepository} marker does NOT
     * auto-scope derived finders (per the {@code PortalInvoicesController} Javadoc).
     */
    Mono<Invoice> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Phase 10d — locate the CRM Invoice corresponding to a QBO Invoice id stored
     * in {@code externalRefs.quickbooks}. Used by the QBO inbound payment webhook
     * to resolve which CRM invoice to mark paid. Indexed at runtime via the
     * existing {@code tenant_status_idx} (post-filters cheaply for the small
     * number of invoices a single tenant has open at any moment).
     */
    @Query("{ 'tenantId': ?0, 'externalRefs.quickbooks': ?1 }")
    Mono<Invoice> findByTenantIdAndQuickbooksExternalRef(UUID tenantId, String qboInvoiceId);

    /**
     * Portal "my invoices" with no company link on the caller's contact — selects only
     * the direct contactId match. Splitting this away from the $or query below avoids
     * a {@code {companyId: null}} predicate that would match every invoice in the tenant
     * with no companyId set.
     */
    Flux<Invoice> findAllByTenantIdAndContactIdOrderByIssuedAtDesc(UUID tenantId, UUID contactId);

    Flux<Invoice> findAllByTenantIdAndContactIdAndStatusOrderByIssuedAtDesc(
            UUID tenantId, UUID contactId, Invoice.Status status);

    /**
     * Portal "my invoices" when the caller's contact has a companyId — invoices match if
     * either the contactId or companyId points at them. Indexed by
     * {@code tenant_contact_idx} for the contact branch and {@code tenant_status_idx}
     * (when status is filtered).
     */
    @Query(value = "{ 'tenantId': ?0, '$or': [ { 'contactId': ?1 }, { 'companyId': ?2 } ] }",
            sort = "{ 'issuedAt': -1 }")
    Flux<Invoice> findAllByTenantAndContactOrCompany(UUID tenantId, UUID contactId, UUID companyId);

    @Query(value = "{ 'tenantId': ?0, 'status': ?3, '$or': [ { 'contactId': ?1 }, { 'companyId': ?2 } ] }",
            sort = "{ 'issuedAt': -1 }")
    Flux<Invoice> findAllByTenantAndContactOrCompanyAndStatus(
            UUID tenantId, UUID contactId, UUID companyId, Invoice.Status status);

    /**
     * "Get Paid" AR / collections (band 4600-4619) — the default-OFF {@code ArAgingSweepJob}'s
     * per-tenant candidate query: every invoice in one of the supplied statuses (the sweep passes
     * {@code [SENT, OVERDUE]}) so it can flip newly-past-due SENT invoices to OVERDUE and re-evaluate
     * already-OVERDUE invoices for higher dunning tiers. The explicit {@code tenantId} predicate scopes
     * the read — the {@code TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived
     * finders. Backed by the existing {@code tenant_status_idx}. Strictly additive (no behavior change
     * to any existing path).
     */
    Flux<Invoice> findAllByTenantIdAndStatusIn(
            UUID tenantId, java.util.Collection<Invoice.Status> statuses);
}
