package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.model.response.TimesheetView;
import com.kumouri.kmodigipresbe.service.contractor.ContractorAccessGuard;
import com.kumouri.kmodigipresbe.service.contractor.ContractorSelfResolver;
import com.kumouri.kmodigipresbe.service.contractor.TimesheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Contractor-scoped timesheet surface (Phase J — J3). A contractor reads ONLY their own
 * timesheet periods and drives the self half of the lifecycle (submit / reopen) — the self
 * user id is resolved from the token via {@link ContractorSelfResolver}, never the request.
 *
 * <p>The list is self-forced. The single read and both mutations funnel through
 * {@link ContractorAccessGuard#requireOwnedTimesheet} (same-404 {@code 4133} if not the
 * caller's) before the {@link TimesheetService} transition runs, so a contractor can never
 * submit/reopen another user's period. Approve / reject stay ADMIN-only on the
 * {@code /timesheets} surface — they are NOT exposed here. Projection records out
 * ({@link TimesheetView}, drops {@code tenantId}) per the J2 projection posture.
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled} (on by default — the J1/J2
 * controller precedent).
 */
@RestController
@RequestMapping("/me/contractor/timesheets")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ContractorTimesheetController {

    private final ContractorSelfResolver self;
    private final ContractorAccessGuard guard;
    private final TimesheetService service;

    /** Lists the caller's own timesheet periods, newest first (self forced). */
    @GetMapping
    public Flux<TimesheetView> list() {
        return self.resolveUserId()
                .flatMapMany(service::findByUser)
                .map(TimesheetView::from);
    }

    /**
     * Returns one of the caller's own timesheets. {@code 4133}/404 (same as not-found) if it
     * is not the caller's.
     */
    @GetMapping("/{id}")
    public Mono<TimesheetView> get(@PathVariable UUID id) {
        return guard.requireOwnedTimesheet(id).map(TimesheetView::from);
    }

    /**
     * Submits one of the caller's own timesheets ({@code OPEN|REJECTED → SUBMITTED}).
     * Ownership is gated first ({@code 4133}/404); an illegal source transition is
     * {@code 4150}/409 from the service.
     */
    @PostMapping("/{id}/submit")
    public Mono<TimesheetView> submit(@PathVariable UUID id) {
        return guard.requireOwnedTimesheet(id)
                .flatMap(owned -> service.submit(id))
                .map(TimesheetView::from);
    }

    /**
     * Reopens one of the caller's own rejected timesheets ({@code REJECTED → OPEN}) to fix
     * and resubmit. Ownership is gated first ({@code 4133}/404); an illegal source transition
     * is {@code 4150}/409 from the service.
     */
    @PostMapping("/{id}/reopen")
    public Mono<TimesheetView> reopen(@PathVariable UUID id) {
        return guard.requireOwnedTimesheet(id)
                .flatMap(owned -> service.reopen(id))
                .map(TimesheetView::from);
    }
}
