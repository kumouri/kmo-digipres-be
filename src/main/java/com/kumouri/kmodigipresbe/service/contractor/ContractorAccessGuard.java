package com.kumouri.kmodigipresbe.service.contractor;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.repository.contractor.ProjectAssignmentRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.ExpenseRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.TimeEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Access-control guard for single-entity contractor operations (Phase J — J2), the
 * contractor analogue of {@link com.kumouri.kmodigipresbe.service.portal.PortalOwnershipGuard}.
 *
 * <p>Every method follows the same pattern:
 * <ol>
 *   <li>Resolve the caller's self {@code (tenantId, userId)} via
 *       {@link ContractorSelfResolver#resolve()} — the single chokepoint; the self id is
 *       from the verified token, never the request.</li>
 *   <li>Load/probe by a <strong>tenant-scoped</strong> finder
 *       ({@code findFirstByTenantIdAndProjectIdAndUserId} / {@code findByTenantIdAndId}) —
 *       cross-tenant leakage is blocked at the query level.</li>
 *   <li>When the relationship is absent (no active assignment) OR the entity is not owned
 *       by the caller: surface the <strong>same</strong> {@code 4132}/{@code 4133} + 404 as
 *       a genuine not-found — the deliberate no-enumeration-oracle confidentiality choice
 *       (the {@code PortalOwnershipGuard} G-D2 posture). A contractor must NOT be able to
 *       distinguish "exists but isn't yours / you aren't on it" from "no such entity".</li>
 * </ol>
 *
 * <p>Stateless {@code @Component} — no new resource-owning bean (the {@code PortalOwnershipGuard}
 * F.2/F.11 lifecycle posture).
 */
@Component
@RequiredArgsConstructor
public class ContractorAccessGuard {

    private final ContractorSelfResolver self;
    private final ProjectAssignmentRepository assignments;
    private final ProjectRepository projects;
    private final TimeEntryRepository timeEntries;
    private final ExpenseRepository expenses;

    // -------------------------------------------------------------------------
    // Public guard methods
    // -------------------------------------------------------------------------

    /**
     * Confirms the caller is <strong>actively</strong> assigned to {@code projectId} and
     * returns the {@link Project}. A missing assignment, an inactive (soft-deleted)
     * assignment, and a non-existent project all return the same {@code 4132}/404 (no
     * enumeration oracle).
     */
    public Mono<Project> requireAssignedProject(UUID projectId) {
        return self.resolve().flatMap(s ->
                assignments.findFirstByTenantIdAndProjectIdAndUserId(s.tenantId(), projectId, s.userId())
                        .filter(a -> a.isActive())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Project not found", 4132, 404)))
                        .flatMap(a -> projects.findByTenantIdAndId(s.tenantId(), projectId)
                                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                        "Project not found", 4132, 404)))));
    }

    /**
     * Resolves the {@link TimeEntry} identified by {@code id} and confirms it belongs to the
     * caller. Both not-found and not-owned return the same {@code 4133}/404 (no enumeration
     * oracle).
     */
    public Mono<TimeEntry> requireOwnedTimeEntry(UUID id) {
        return self.resolve().flatMap(s ->
                timeEntries.findByTenantIdAndId(s.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Time entry not found", 4133, 404)))
                        .flatMap(entry -> ownedBy(entry.getUserId(), s.userId())
                                ? Mono.just(entry)
                                : Mono.error(() -> new DigiPresBeException(
                                        "Time entry not found", 4133, 404))));
    }

    /**
     * Resolves the {@link Expense} identified by {@code id} and confirms it belongs to the
     * caller. Both not-found and not-owned return the same {@code 4133}/404 (no enumeration
     * oracle).
     */
    public Mono<Expense> requireOwnedExpense(UUID id) {
        return self.resolve().flatMap(s ->
                expenses.findByTenantIdAndId(s.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Expense not found", 4133, 404)))
                        .flatMap(expense -> ownedBy(expense.getUserId(), s.userId())
                                ? Mono.just(expense)
                                : Mono.error(() -> new DigiPresBeException(
                                        "Expense not found", 4133, 404))));
    }

    // -------------------------------------------------------------------------
    // Private ownership predicate — never matches null == null
    // -------------------------------------------------------------------------

    private static boolean ownedBy(UUID entryUserId, UUID selfUserId) {
        return entryUserId != null && entryUserId.equals(selfUserId);
    }
}
