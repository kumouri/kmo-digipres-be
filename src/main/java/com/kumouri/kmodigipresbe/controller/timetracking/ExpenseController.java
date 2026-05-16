package com.kumouri.kmodigipresbe.controller.timetracking;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.Expense.ApprovalStatus;
import com.kumouri.kmodigipresbe.service.timetracking.ExpenseService;
import com.kumouri.kmodigipresbe.service.timetracking.ExpenseService.InvoiceFromExpensesRequest;
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
 * REST API for {@link Expense} — expense submission, approval, and invoicing (Phase D — D-D8, D-D10).
 *
 * <p>Approval ({@code approve}, {@code reject}) is gated
 * {@code RoleGuard.requireRole("ADMIN")} (D-D10 / Phase-C {@code MilestoneController} precedent).
 * DELETE is also admin-gated.
 *
 * <p>{@code @IdempotentRoute} on {@code /invoice-from-expenses} (side-effecting; domain
 * guard is the billingStatus anchor in the service).
 *
 * <p>Receipt upload reuses the existing {@code /attachments} flow verbatim — zero new
 * file-storage endpoints here (D-D12). The client presigns with
 * {@code subjectType="EXPENSE", subjectId=<expenseId>}, PUTs bytes to S3, and registers
 * the attachment via {@code POST /attachments}.
 */
@RestController
@RequestMapping("/expenses")
@ConditionalOnProperty(prefix = "kmosf.modules.timetracking", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService service;

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @GetMapping("/by-user/{userId}")
    public Flux<Expense> listByUser(@PathVariable UUID userId) {
        return service.findByUser(userId);
    }

    @GetMapping("/by-approval-status")
    public Flux<Expense> listByApprovalStatus(@RequestParam ApprovalStatus status) {
        return service.findByApprovalStatus(status);
    }

    @GetMapping("/{id}")
    public Mono<Expense> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Expense> create(@RequestBody Expense body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Expense> update(@PathVariable UUID id, @RequestBody Expense body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    // -------------------------------------------------------------------------
    // Approval workflow (D-D10)
    // -------------------------------------------------------------------------

    /**
     * Approves an expense. ADMIN-only (RoleGuard.requireRole at the head of the chain).
     */
    @PostMapping("/{id}/approve")
    public Mono<Expense> approve(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.approve(id));
    }

    /**
     * Rejects an expense. ADMIN-only. {@code reason} is required (error 3516 if blank).
     */
    @PostMapping("/{id}/reject")
    public Mono<Expense> reject(@PathVariable UUID id, @RequestParam String reason) {
        return RoleGuard.requireRole("ADMIN").then(service.reject(id, reason));
    }

    // -------------------------------------------------------------------------
    // Invoice-from-expenses (D-D8)
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT invoice from approved, billable, unbilled expenses.
     * {@code @IdempotentRoute} (belt-and-suspenders; domain guard is the
     * {@code billingStatus==UNBILLED} + APPROVED filter + explicit isEmpty in the service).
     */
    @PostMapping("/invoice-from-expenses")
    @IdempotentRoute
    public Mono<Invoice> invoiceFromExpenses(@RequestBody InvoiceFromExpensesRequest request) {
        return service.createInvoiceFromExpenses(request);
    }
}
