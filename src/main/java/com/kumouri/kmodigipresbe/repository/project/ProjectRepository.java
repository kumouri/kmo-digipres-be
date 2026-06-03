package com.kumouri.kmodigipresbe.repository.project;

import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit-tenant finders per the Ticket-repo precedent. Auto-scoped base-repo
 * methods ({@code findById}, {@code save}, etc.) apply the {@code tenantId} predicate
 * automatically via {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository};
 * derived finders pass {@code tenantId} explicitly for index-friendliness (C-D2).
 *
 * <p>Portal contact/company finders (Phase G — G-D9) mirror the
 * {@code InvoiceRepository} portal-finder precedent. Note that the contact field
 * on {@link Project} is {@code primaryContactId} (not {@code contactId}) — the
 * derived finders and {@code $or} query use this exact field name.
 * Derived finders require an explicit {@code tenantId} argument — the
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders (per the
 * {@code PortalInvoicesController} Javadoc).
 */
public interface ProjectRepository extends TenantScopedReactiveMongoRepository<Project, UUID> {

    Flux<Project> findAllByTenantId(UUID tenantId);

    Mono<Project> findByTenantIdAndId(UUID tenantId, UUID id);

    Mono<Project> findByTenantIdAndCode(UUID tenantId, String code);

    Mono<Project> findFirstByTenantIdAndDealId(UUID tenantId, UUID dealId);

    Mono<Boolean> existsByTenantIdAndDealId(UUID tenantId, UUID dealId);

    /**
     * Portal "my projects" when the caller's contact has no company link — selects
     * only the direct primaryContactId match. Avoids the {@code {companyId: null}}
     * predicate trap in the {@code $or}-based query below.
     */
    Flux<Project> findAllByTenantIdAndPrimaryContactIdOrderByCreatedAtDesc(
            UUID tenantId, UUID primaryContactId);

    /**
     * Portal "my projects" when the caller's contact has a companyId — projects
     * match if either the primaryContactId or companyId points at them. Mirrors
     * {@code InvoiceRepository.findAllByTenantAndContactOrCompany}.
     */
    @Query(value = "{ 'tenantId': ?0, '$or': [ { 'primaryContactId': ?1 }, { 'companyId': ?2 } ] }",
            sort = "{ 'createdAt': -1 }")
    Flux<Project> findAllByTenantAndPrimaryContactOrCompany(
            UUID tenantId, UUID primaryContactId, UUID companyId);

    /**
     * Phase 3 (NMM coverage-window automation) — selects a tenant's Projects whose coverage window
     * is still open ({@code coverageWindowEndsAt != null && coverageWindowEndsAt > now}), the
     * default-OFF {@code CoverageNudgeJob}'s active-coverage selector. <strong>Strictly additive</strong>
     * (new derived finder) — every existing {@code ProjectService}/{@code ProjectRepository} behaviour
     * is unchanged (the Phase-1 {@code ContactRepository.findByTenantAndPhoneNumber} precedent for a
     * strictly-additive finder). The {@code GreaterThan} on a nullable field also excludes
     * {@code coverageWindowEndsAt == null} (Mongo {@code $gt} never matches a missing/null field), so
     * legacy/non-coverage Projects are naturally skipped. Carries an explicit {@code tenantId}
     * predicate ({@code TenantScopedReactiveMongoRepository} does NOT auto-scope derived finders).
     */
    Flux<Project> findAllByTenantIdAndCoverageWindowEndsAtAfter(UUID tenantId, Instant now);
}
