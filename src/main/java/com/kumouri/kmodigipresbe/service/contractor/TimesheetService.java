package com.kumouri.kmodigipresbe.service.contractor;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet.Status;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.repository.contractor.TimesheetRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.TimeEntryRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the {@link Timesheet} submit→approve lifecycle (Phase J — J3), the invoicing
 * gate that flips {@code TimeEntry.approved} in lock-step with the period's status.
 *
 * <h2>Lifecycle</h2>
 * {@code OPEN|REJECTED → SUBMITTED} (contractor self, {@link #submit}); {@code SUBMITTED →
 * APPROVED} (ADMIN, {@link #approve}); {@code SUBMITTED → REJECTED} (ADMIN, {@link #reject},
 * reason required); {@code REJECTED → OPEN} (contractor self, {@link #reopen} — fix and
 * resubmit). Illegal transitions are validated against an explicit {@code Set<String>}
 * (the {@link com.kumouri.kmodigipresbe.service.timetracking.ExpenseService} /
 * {@code MilestoneService.ILLEGAL_TRANSITIONS} pattern) → error 4150.
 *
 * <h2>Approval flips the member entries (the money gate)</h2>
 * {@code approve} bulk-flips every member {@link TimeEntry#isApproved()} to {@code true};
 * {@code reject}/{@code reopen} flip it back to {@code false}. The timesheet is the single
 * writer of the flag, so invoice-from-time stays an O(1) {@code e.isApproved()} filter with
 * no drift. Entries that are already {@code billingStatus == INVOICED} are EXCLUDED from
 * every flip — billed money is never un/re-approved.
 *
 * <h2>§9 invariant — switchIfEmpty only for genuine not-found</h2>
 * {@link #findById} uses {@code switchIfEmpty} for the 4152 not-found path only. The
 * lifecycle transitions are explicit {@code ILLEGAL_TRANSITIONS} guards, never
 * {@code switchIfEmpty(transition)}.
 */
@Service
@RequiredArgsConstructor
public class TimesheetService {

    private static final Set<String> ILLEGAL_TRANSITIONS = Set.of(
            // submit (→ SUBMITTED): only OPEN and REJECTED are legal sources
            "SUBMITTED->SUBMITTED",
            "APPROVED->SUBMITTED",
            // approve (→ APPROVED): only SUBMITTED is legal
            "OPEN->APPROVED",
            "APPROVED->APPROVED",
            "REJECTED->APPROVED",
            // reject (→ REJECTED): only SUBMITTED is legal
            "OPEN->REJECTED",
            "APPROVED->REJECTED",
            "REJECTED->REJECTED",
            // reopen (→ OPEN): only REJECTED is legal
            "OPEN->OPEN",
            "SUBMITTED->OPEN",
            "APPROVED->OPEN"
    );

    private final TimesheetRepository timesheets;
    private final TimeEntryRepository timeEntries;
    private final DomainEventPublisher events;

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Loads a timesheet by id within the current tenant. Genuine not-found → 4152/404 (the
     * admin lifecycle path; the contractor self-surface gates via
     * {@code ContractorAccessGuard.requireOwnedTimesheet} which surfaces the same-404 4133).
     */
    public Mono<Timesheet> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> timesheets.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Timesheet not found", 4152, 404)));
    }

    public Flux<Timesheet> findByUser(UUID userId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> timesheets.findAllByTenantIdAndUserIdOrderByPeriodStartDesc(
                        ctx.tenantId(), userId));
    }

    public Flux<Timesheet> findByStatus(Status status) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> timesheets.findAllByTenantIdAndStatus(ctx.tenantId(), status));
    }

    // -------------------------------------------------------------------------
    // Lifecycle — contractor self
    // -------------------------------------------------------------------------

    /**
     * Submits a timesheet (contractor self). {@code OPEN|REJECTED → SUBMITTED}, stamps
     * {@code submittedAt}. Illegal source → 4150/409. Ownership is enforced at the controller
     * via {@code ContractorAccessGuard.requireOwnedTimesheet}.
     */
    public Mono<Timesheet> submit(UUID timesheetId) {
        return findById(timesheetId).flatMap(sheet -> {
            String transitionKey = sheet.getStatus().name() + "->SUBMITTED";
            if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                return Mono.error(new DigiPresBeException(
                        "Transition " + transitionKey + " is not permitted", 4150, 409));
            }
            sheet.setStatus(Status.SUBMITTED);
            sheet.setSubmittedAt(Instant.now());
            return timesheets.save(sheet)
                    .flatMap(saved -> {
                        events.publish(DomainEvent.of(DomainEventType.TIMESHEET_SUBMITTED,
                                saved.getTenantId(), saved.getId(),
                                Map.of("userId", saved.getUserId().toString())));
                        return Mono.just(saved);
                    });
        });
    }

    /**
     * Reopens a rejected timesheet (contractor self). {@code REJECTED → OPEN}, bulk-flips the
     * member entries' {@code approved} back to {@code false} (excluding INVOICED). Illegal
     * source → 4150/409.
     */
    public Mono<Timesheet> reopen(UUID timesheetId) {
        return findById(timesheetId).flatMap(sheet -> {
            String transitionKey = sheet.getStatus().name() + "->OPEN";
            if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                return Mono.error(new DigiPresBeException(
                        "Transition " + transitionKey + " is not permitted", 4150, 409));
            }
            sheet.setStatus(Status.OPEN);
            return setMemberApproval(sheet, false)
                    .then(timesheets.save(sheet))
                    .flatMap(saved -> {
                        events.publish(DomainEvent.of(DomainEventType.TIMESHEET_REOPENED,
                                saved.getTenantId(), saved.getId(),
                                Map.of("userId", saved.getUserId().toString())));
                        return Mono.just(saved);
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Lifecycle — ADMIN (gated at the controller via RoleGuard.requireRole("ADMIN"))
    // -------------------------------------------------------------------------

    /**
     * Approves a timesheet (ADMIN). {@code SUBMITTED → APPROVED}, stamps
     * {@code approvedBy}(=ctx.userId())/{@code approvedAt}, and bulk-flips every member
     * entry's {@code approved} to {@code true} (EXCLUDING INVOICED entries — billed money is
     * never re-approved). Illegal source → 4150/409.
     */
    public Mono<Timesheet> approve(UUID timesheetId) {
        return TenantContextHolder.required().flatMap(ctx ->
                findById(timesheetId).flatMap(sheet -> {
                    String transitionKey = sheet.getStatus().name() + "->APPROVED";
                    if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                        return Mono.error(new DigiPresBeException(
                                "Transition " + transitionKey + " is not permitted", 4150, 409));
                    }
                    sheet.setStatus(Status.APPROVED);
                    sheet.setApprovedBy(ctx.userId());
                    sheet.setApprovedAt(Instant.now());
                    return setMemberApproval(sheet, true)
                            .then(timesheets.save(sheet))
                            .flatMap(saved -> {
                                events.publish(DomainEvent.of(DomainEventType.TIMESHEET_APPROVED,
                                        saved.getTenantId(), saved.getId(),
                                        Map.of("approvedBy", ctx.userId().toString())));
                                return Mono.just(saved);
                            });
                }));
    }

    /**
     * Rejects a timesheet (ADMIN). {@code SUBMITTED → REJECTED}, {@code reason} required
     * (4151/400 if blank — stored in {@code note}), stamps {@code approvedBy}/{@code approvedAt}
     * (the decider), and bulk-flips every member entry's {@code approved} back to
     * {@code false} (EXCLUDING INVOICED). Illegal source → 4150/409.
     */
    public Mono<Timesheet> reject(UUID timesheetId, String reason) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "A rejection reason is required", 4151, 400));
        }
        return TenantContextHolder.required().flatMap(ctx ->
                findById(timesheetId).flatMap(sheet -> {
                    String transitionKey = sheet.getStatus().name() + "->REJECTED";
                    if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                        return Mono.error(new DigiPresBeException(
                                "Transition " + transitionKey + " is not permitted", 4150, 409));
                    }
                    sheet.setStatus(Status.REJECTED);
                    sheet.setApprovedBy(ctx.userId());
                    sheet.setApprovedAt(Instant.now());
                    sheet.setNote(reason);
                    return setMemberApproval(sheet, false)
                            .then(timesheets.save(sheet))
                            .flatMap(saved -> {
                                events.publish(DomainEvent.of(DomainEventType.TIMESHEET_REJECTED,
                                        saved.getTenantId(), saved.getId(),
                                        Map.of("rejectedBy", ctx.userId().toString(),
                                                "reason", reason)));
                                return Mono.just(saved);
                            });
                }));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Bulk-flips the {@code approved} flag of every member {@link TimeEntry} of {@code sheet}
     * to {@code approved}, EXCLUDING entries whose {@code billingStatus == INVOICED} (billed
     * money is never un/re-approved). Completes empty when there are no member entries to
     * touch (an empty period is legal). Loaded via {@code tenant_timesheet_idx}.
     */
    private Mono<Void> setMemberApproval(Timesheet sheet, boolean approved) {
        return timeEntries.findAllByTenantIdAndTimesheetId(sheet.getTenantId(), sheet.getId())
                .filter(e -> e.getBillingStatus() != BillingStatus.INVOICED)
                .map(e -> {
                    e.setApproved(approved);
                    return e;
                })
                .collectList()
                .flatMap(toSave -> toSave.isEmpty()
                        ? Mono.empty()
                        : timeEntries.saveAll(toSave).then());
    }
}
