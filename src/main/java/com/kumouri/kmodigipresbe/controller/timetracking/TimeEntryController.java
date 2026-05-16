package com.kumouri.kmodigipresbe.controller.timetracking;

import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.service.timetracking.TimeEntryService;
import com.kumouri.kmodigipresbe.service.timetracking.TimeEntryService.InvoiceFromTimeRequest;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API for {@link TimeEntry} — the time tracking vertical (Phase D — D-D4, D-D5, D-D6).
 *
 * <p>Manual entry ({@code POST /time-entries}) is the first-class create path (Decision D9).
 * Timer is sugar: {@code POST /time-entries/timer/start} + {@code /timer/stop}.
 *
 * <p>{@code @IdempotentRoute} is applied to side-effecting POSTs with external effects:
 * {@code /timer/stop} (closes an entry + may split), {@code /invoice-from-time}
 * (creates a DRAFT invoice). This is the belt-and-suspenders at the HTTP layer;
 * domain-level idempotency is in the service (explicit boolean guards / billingStatus anchor).
 *
 * <p>{@code RoleGuard.requireRole("ADMIN")} gates DELETE (D-D10 / C-D5 pattern).
 *
 * <p>{@code @ConditionalOnProperty(prefix="kmosf.modules.timetracking", name="enabled",
 * matchIfMissing=true)} gates the entire controller (D-D10 / Phase-C {@code ProjectController}
 * precedent).
 */
@RestController
@RequestMapping("/time-entries")
@ConditionalOnProperty(prefix = "kmosf.modules.timetracking", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TimeEntryController {

    private final TimeEntryService service;

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @GetMapping("/by-user/{userId}")
    public Flux<TimeEntry> listByUser(@PathVariable UUID userId) {
        return service.findByUser(userId);
    }

    @GetMapping("/weekly")
    public Flux<TimeEntry> listWeekly(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam UUID userId) {
        return service.findWeekly(from, to, userId);
    }

    @GetMapping("/{id}")
    public Mono<TimeEntry> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping("/timer/running")
    public Mono<TimeEntry> getRunningTimer(@RequestParam UUID userId) {
        return service.findRunningTimer(userId);
    }

    /**
     * Manual time entry — the first-class create path (Decision D9).
     * Accepts an optional {@code zoneId} header for the midnight-split boundary
     * calculation (D-D3b zone precedence).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TimeEntry> create(
            @RequestBody TimeEntry body,
            @RequestHeader(value = "X-Zone-Id", required = false) String zoneId) {
        return service.create(body, zoneId);
    }

    @PutMapping("/{id}")
    public Mono<TimeEntry> update(
            @PathVariable UUID id,
            @RequestBody TimeEntry body,
            @RequestHeader(value = "X-Zone-Id", required = false) String zoneId) {
        return service.update(id, body, zoneId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    // -------------------------------------------------------------------------
    // Timer API (D-D3a)
    // -------------------------------------------------------------------------

    /**
     * Starts a timer. No {@code @IdempotentRoute} — the explicit boolean guard in the
     * service (error 3505 if already running) is sufficient. A running timer is idempotent
     * by construction (the second start returns 409, not a second entry).
     */
    @PostMapping("/timer/start")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TimeEntry> startTimer(@RequestBody TimeEntry body) {
        return service.startTimer(body);
    }

    /**
     * Stops the running timer. {@code @IdempotentRoute} because it closes an entry +
     * may trigger a split (external side-effect). The {@code zoneId} param is the browser
     * zone for the midnight-split boundary (D-D3b). {@code endedAt} allows back-dating
     * the stop if the UI received the click at a known time.
     */
    @PostMapping("/timer/stop")
    @IdempotentRoute
    public Mono<List<TimeEntry>> stopTimer(
            @RequestParam UUID userId,
            @RequestParam(required = false) Instant endedAt,
            @RequestParam(required = false) String zoneId) {
        return service.stopTimer(userId, endedAt, zoneId);
    }

    // -------------------------------------------------------------------------
    // Invoice-from-time (D-D6)
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT invoice from unbilled time entries. {@code @IdempotentRoute}
     * (belt-and-suspenders; domain guard is the {@code billingStatus==UNBILLED} filter
     * + explicit isEmpty check in the service — D-D6 §9 invariant).
     */
    @PostMapping("/invoice-from-time")
    @IdempotentRoute
    public Mono<Invoice> invoiceFromTime(@RequestBody InvoiceFromTimeRequest request) {
        return service.createInvoiceFromTime(request);
    }
}
