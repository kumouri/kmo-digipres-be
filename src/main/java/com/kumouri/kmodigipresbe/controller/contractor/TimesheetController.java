package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.model.contractor.Timesheet;
import com.kumouri.kmodigipresbe.model.contractor.Timesheet.Status;
import com.kumouri.kmodigipresbe.service.contractor.TimesheetService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Admin timesheet review surface (Phase J — J3) — all ADMIN-gated via
 * {@code RoleGuard.requireRole("ADMIN")}. The cross-user review queue + the approve/reject
 * half of the lifecycle (the contractor self half — submit/reopen — lives on
 * {@link ContractorTimesheetController}). Returns the raw {@link Timesheet} entity (admin
 * sees everything — the {@code ProjectAssignmentController} admin-returns-entity precedent).
 *
 * <p>Approve flips the period's member entries' {@code TimeEntry.approved} to {@code true}
 * (the invoice/payout gate); reject flips it back to {@code false}. Reject requires a reason
 * ({@code 4151}/400 if blank). An illegal lifecycle transition is {@code 4150}/409; a
 * timesheet that does not exist for the tenant is {@code 4152}/404.
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled} (on by default — the J1/J2
 * controller precedent).
 */
@RestController
@RequestMapping("/timesheets")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TimesheetController {

    private final TimesheetService service;

    /**
     * The cross-user review queue. {@code status} defaults to {@code SUBMITTED} (the
     * pending-approval queue); any {@link Status} may be requested.
     */
    @GetMapping
    public Flux<Timesheet> list(
            @RequestParam(required = false, defaultValue = "SUBMITTED") Status status) {
        return RoleGuard.requireRole("ADMIN").thenMany(service.findByStatus(status));
    }

    @GetMapping("/{id}")
    public Mono<Timesheet> get(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.findById(id));
    }

    /**
     * Approves a submitted timesheet ({@code SUBMITTED → APPROVED}) and flips its member
     * entries' {@code approved} to {@code true}.
     */
    @PostMapping("/{id}/approve")
    public Mono<Timesheet> approve(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.approve(id));
    }

    /**
     * Rejects a submitted timesheet ({@code SUBMITTED → REJECTED}). {@code reason} is required
     * ({@code 4151}/400 if blank); it is stored in the timesheet {@code note}. Flips the
     * member entries' {@code approved} back to {@code false}.
     */
    @PostMapping("/{id}/reject")
    public Mono<Timesheet> reject(@PathVariable UUID id, @RequestParam String reason) {
        return RoleGuard.requireRole("ADMIN").then(service.reject(id, reason));
    }
}
