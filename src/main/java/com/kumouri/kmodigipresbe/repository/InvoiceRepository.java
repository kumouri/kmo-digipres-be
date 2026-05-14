package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface InvoiceRepository extends TenantScopedReactiveMongoRepository<Invoice, UUID> {

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
}
