package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import com.kumouri.kmodigipresbe.service.contractor.ContractorAccessGuard;
import com.kumouri.kmodigipresbe.service.contractor.ContractorSelfResolver;
import com.kumouri.kmodigipresbe.service.timetracking.TimeEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
 * Contractor-scoped time surface (Phase J — J2). A contractor reads and writes ONLY their
 * own time entries — the self user id is resolved from the token via
 * {@link ContractorSelfResolver}, never taken from the request.
 *
 * <p>Reads delegate to {@link TimeEntryService} with the self id forced. The manual-log POST
 * rejects a body carrying a foreign {@code userId} ({@code != self}) with {@code 4134}/400
 * before any write (a null {@code userId} is stamped as self by the service). The timer and
 * the single-entry update are self-scoped; the update funnels through
 * {@link ContractorAccessGuard#requireOwnedTimeEntry} so a contractor cannot edit another
 * user's entry (same-404).
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled} (on by default).
 */
@RestController
@RequestMapping("/me/contractor/time")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ContractorTimeController {

    private final ContractorSelfResolver self;
    private final ContractorAccessGuard guard;
    private final TimeEntryService service;

    /** Lists the caller's own time entries (self forced). */
    @GetMapping
    public Flux<TimeEntry> list() {
        return self.resolveUserId().flatMapMany(service::findByUser);
    }

    /** The caller's own entries within a window (self forced). */
    @GetMapping("/weekly")
    public Flux<TimeEntry> weekly(@RequestParam Instant from, @RequestParam Instant to) {
        return self.resolveUserId().flatMapMany(uid -> service.findWeekly(from, to, uid));
    }

    /**
     * Manual time log as self. A body {@code userId} that is non-null and {@code != self} is
     * rejected with {@code 4134}/400 (cross-user write); a null {@code userId} is stamped as
     * self by the service.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TimeEntry> log(
            @RequestBody TimeEntry body,
            @RequestHeader(value = "X-Zone-Id", required = false) String zoneId) {
        return self.resolveUserId().flatMap(uid -> {
            if (body.getUserId() != null && !body.getUserId().equals(uid)) {
                return Mono.error(new DigiPresBeException(
                        "A contractor may only log time for themselves", 4134, 400));
            }
            body.setUserId(uid);
            return service.create(body, zoneId);
        });
    }

    /** Starts the caller's own timer (self forced onto the body). */
    @PostMapping("/timer/start")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TimeEntry> startTimer(@RequestBody(required = false) TimeEntry body) {
        return self.resolveUserId().flatMap(uid -> {
            TimeEntry b = body != null ? body : new TimeEntry();
            b.setUserId(uid);
            return service.startTimer(b);
        });
    }

    /** Stops the caller's own running timer (self forced). */
    @PostMapping("/timer/stop")
    @IdempotentRoute
    public Mono<List<TimeEntry>> stopTimer(
            @RequestParam(required = false) Instant endedAt,
            @RequestParam(required = false) String zoneId) {
        return self.resolveUserId().flatMap(uid -> service.stopTimer(uid, endedAt, zoneId));
    }

    /**
     * Updates one of the caller's own entries. {@link ContractorAccessGuard#requireOwnedTimeEntry}
     * gates ownership first ({@code 4133}/404 if not the caller's), then the existing
     * service update applies the patch.
     */
    @PutMapping("/{id}")
    public Mono<TimeEntry> update(
            @PathVariable UUID id,
            @RequestBody TimeEntry body,
            @RequestHeader(value = "X-Zone-Id", required = false) String zoneId) {
        return guard.requireOwnedTimeEntry(id).flatMap(owned -> service.update(id, body, zoneId));
    }
}
