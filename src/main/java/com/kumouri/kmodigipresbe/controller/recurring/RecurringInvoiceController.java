package com.kumouri.kmodigipresbe.controller.recurring;

import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice.Status;
import com.kumouri.kmodigipresbe.service.recurring.RecurringInvoiceService;
import com.kumouri.kmodigipresbe.service.recurring.RecurringInvoiceSpawnService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for {@link RecurringInvoice} (Phase E — E-D12). Raw-entity in/out (the
 * codebase convention — no MapStruct DTOs). Module-gated
 * {@code @ConditionalOnProperty(kmosf.modules.billing-stripe, matchIfMissing=true)}.
 *
 * <p>{@code DELETE} is ADMIN-gated ({@code RoleGuard.requireRole("ADMIN")}, the
 * Phase-C/D precedent). {@code POST /{id}/spawn-now} is {@code @IdempotentRoute}
 * (it creates a real billable invoice — the {@code @IdempotentRoute} is the belt
 * over the {@code RecurringInvoiceOccurrence} ledger guarantee; AC-E2's
 * spawn-twice idempotency is exercised through this endpoint).
 *
 * <p>Validation errors surface from the service: 3601 templateName, 3602
 * lineItems, 3603 rrule, 3604 seedAt, 1300 malformed RRULE (reused), 3605
 * not-found, 3606 illegal status transition, 3607 modify-ENDED.
 */
@RestController
@RequestMapping("/recurring-invoices")
@ConditionalOnProperty(prefix = "kmosf.modules.billing-stripe", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class RecurringInvoiceController {

    private final RecurringInvoiceService service;
    private final RecurringInvoiceSpawnService spawnService;

    @GetMapping
    public Flux<RecurringInvoice> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<RecurringInvoice> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RecurringInvoice> create(@RequestBody RecurringInvoice body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<RecurringInvoice> update(@PathVariable UUID id,
                                         @RequestBody RecurringInvoice body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/status")
    public Mono<RecurringInvoice> setStatus(@PathVariable UUID id,
                                            @RequestParam Status status) {
        return service.setStatus(id, status);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    /**
     * Manual single-template spawn (E-D12). {@code @IdempotentRoute}: a retry with
     * the same {@code Idempotency-Key} replays the original 2xx; the
     * {@code RecurringInvoiceOccurrence} unique ledger additionally guarantees
     * exactly one invoice per period even without the key. Returns 200 (no body —
     * the spawned invoice is observable via GET /invoices and the
     * RECURRING_INVOICE_SPAWNED event).
     */
    @PostMapping("/{id}/spawn-now")
    @IdempotentRoute
    public Mono<Void> spawnNow(@PathVariable UUID id) {
        return spawnService.spawnNow(id);
    }
}
