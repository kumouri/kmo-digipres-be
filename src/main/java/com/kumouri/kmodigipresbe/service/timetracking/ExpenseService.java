package com.kumouri.kmodigipresbe.service.timetracking;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.model.timetracking.Expense.ApprovalStatus;
import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry.BillingStatus;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.repository.timetracking.ExpenseRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the {@link Expense} lifecycle (Phase D — D-D2, D-D8, D-D10).
 *
 * <h2>Approval workflow (D-D10)</h2>
 * {@code PENDING → APPROVED | REJECTED} via {@code approve} / {@code reject}.
 * Approval/rejection is gated {@code RoleGuard.requireRole("ADMIN")} at the controller
 * layer. Illegal transitions are validated against an explicit {@code Set<String>}
 * (the {@code MilestoneService.ILLEGAL_TRANSITIONS} / {@code ProjectService} pattern).
 *
 * <h2>§9 invariant — explicit boolean idempotency, NEVER switchIfEmpty(create)</h2>
 * {@link #createInvoiceFromExpenses}: filtered (APPROVED + billable + UNBILLED) candidate
 * set + explicit {@code if (candidates.isEmpty())} → error 3530. No switchIfEmpty.
 * {@code switchIfEmpty} is used only for genuine not-found (3511 entity not found path).
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final Set<String> ILLEGAL_TRANSITIONS = Set.of(
            "APPROVED->PENDING",
            "REJECTED->PENDING",
            "APPROVED->APPROVED",
            "REJECTED->REJECTED"
    );

    private final ExpenseRepository expenses;
    private final ProjectRepository projects;
    private final InvoiceService invoiceService;
    private final DomainEventPublisher events;

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public Flux<Expense> findByUser(UUID userId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> expenses.findAllByTenantIdAndUserIdOrderByIncurredOnDesc(
                        ctx.tenantId(), userId));
    }

    public Flux<Expense> findByApprovalStatus(ApprovalStatus status) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> expenses.findAllByTenantIdAndApprovalStatus(ctx.tenantId(), status));
    }

    public Mono<Expense> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> expenses.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Expense not found", 3511, 404)));
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    public Mono<Expense> create(Expense body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getUserId() == null) {
                body.setUserId(ctx.userId());
            }
            if (body.getDescription() == null || body.getDescription().isBlank()) {
                return Mono.error(new DigiPresBeException("description is required", 3512, 400));
            }
            if (body.getAmount() == null || body.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return Mono.error(new DigiPresBeException("amount must be > 0", 3513, 400));
            }
            if (body.getIncurredOn() == null) {
                return Mono.error(new DigiPresBeException("incurredOn is required", 3514, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            body.setApprovalStatus(ApprovalStatus.PENDING);
            body.setBillingStatus(BillingStatus.UNBILLED);
            body.setInvoicedInvoiceId(null);
            body.setApprovedByUserId(null);
            body.setDecidedAt(null);
            body.setRejectionReason(null);
            return expenses.save(body)
                    .flatMap(saved -> {
                        events.publish(DomainEvent.of(DomainEventType.EXPENSE_SUBMITTED,
                                ctx.tenantId(), saved.getId(),
                                Map.of("userId", saved.getUserId().toString())));
                        return Mono.just(saved);
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public Mono<Expense> update(UUID id, Expense patch) {
        return findById(id).flatMap(existing -> {
            if (existing.getBillingStatus() == BillingStatus.INVOICED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot edit an INVOICED expense", 3517, 409));
            }
            if (patch.getDescription() != null) {
                if (patch.getDescription().isBlank()) {
                    return Mono.error(new DigiPresBeException("description is required", 3512, 400));
                }
                existing.setDescription(patch.getDescription());
            }
            if (patch.getAmount() != null) {
                if (patch.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    return Mono.error(new DigiPresBeException("amount must be > 0", 3513, 400));
                }
                existing.setAmount(patch.getAmount());
            }
            if (patch.getIncurredOn() != null) existing.setIncurredOn(patch.getIncurredOn());
            if (patch.getCategory() != null)    existing.setCategory(patch.getCategory());
            if (patch.getMarkupPercent() != null) existing.setMarkupPercent(patch.getMarkupPercent());
            if (patch.getProjectId() != null)   existing.setProjectId(patch.getProjectId());
            if (patch.getTaskId() != null)       existing.setTaskId(patch.getTaskId());
            existing.setBillable(patch.isBillable());
            // Allow setting receiptAttachmentId (denormalized pointer, D-D12)
            if (patch.getReceiptAttachmentId() != null) {
                existing.setReceiptAttachmentId(patch.getReceiptAttachmentId());
            }
            return expenses.save(existing);
        });
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(e -> expenses.deleteById(e.getId()));
    }

    // -------------------------------------------------------------------------
    // Approval workflow (D-D10)
    // -------------------------------------------------------------------------

    /**
     * Approves an expense. ADMIN-only (enforced at controller via RoleGuard).
     */
    public Mono<Expense> approve(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                findById(id).flatMap(existing -> {
                    if (existing.getBillingStatus() == BillingStatus.INVOICED) {
                        return Mono.error(new DigiPresBeException(
                                "Cannot approve an INVOICED expense", 3517, 409));
                    }
                    String transitionKey = existing.getApprovalStatus().name() + "->APPROVED";
                    if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                        return Mono.error(new DigiPresBeException(
                                "Transition " + transitionKey + " is not permitted", 3515, 409));
                    }
                    existing.setApprovalStatus(ApprovalStatus.APPROVED);
                    existing.setApprovedByUserId(ctx.userId());
                    existing.setDecidedAt(Instant.now());
                    existing.setRejectionReason(null);
                    return expenses.save(existing)
                            .flatMap(saved -> {
                                events.publish(DomainEvent.of(DomainEventType.EXPENSE_APPROVED,
                                        ctx.tenantId(), saved.getId(),
                                        Map.of("approvedByUserId", ctx.userId().toString())));
                                return Mono.just(saved);
                            });
                }));
    }

    /**
     * Rejects an expense. ADMIN-only (enforced at controller via RoleGuard).
     * {@code reason} is required (error 3516 if blank).
     */
    public Mono<Expense> reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "A rejection reason is required", 3516, 400));
        }
        return TenantContextHolder.required().flatMap(ctx ->
                findById(id).flatMap(existing -> {
                    if (existing.getBillingStatus() == BillingStatus.INVOICED) {
                        return Mono.error(new DigiPresBeException(
                                "Cannot reject an INVOICED expense", 3517, 409));
                    }
                    String transitionKey = existing.getApprovalStatus().name() + "->REJECTED";
                    if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                        return Mono.error(new DigiPresBeException(
                                "Transition " + transitionKey + " is not permitted", 3515, 409));
                    }
                    existing.setApprovalStatus(ApprovalStatus.REJECTED);
                    existing.setApprovedByUserId(ctx.userId());
                    existing.setDecidedAt(Instant.now());
                    existing.setRejectionReason(reason);
                    return expenses.save(existing)
                            .flatMap(saved -> {
                                events.publish(DomainEvent.of(DomainEventType.EXPENSE_REJECTED,
                                        ctx.tenantId(), saved.getId(),
                                        Map.of("rejectedByUserId", ctx.userId().toString(),
                                                "reason", reason)));
                                return Mono.just(saved);
                            });
                }));
    }

    // -------------------------------------------------------------------------
    // Invoice-from-expenses (D-D8)
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT invoice from approved, billable, unbilled expenses via the existing
     * {@link InvoiceService#create(Invoice)} (D-D8 / §9 item 3).
     *
     * <p><strong>Eligibility:</strong> only {@code APPROVED + billable + UNBILLED} expenses.
     * A {@code PENDING} (unapproved) expense is not eligible — 3530.
     *
     * <p><strong>Idempotency:</strong> the {@code billingStatus==UNBILLED} (+ APPROVED) filter
     * is the explicit domain guard. A re-invoke finds zero candidates and returns 3530.
     * The {@code @IdempotentRoute} at the controller is the belt-and-suspenders.
     *
     * <p>Does NOT use {@code switchIfEmpty} around any conditional create (§9 invariant).
     */
    public Mono<Invoice> createInvoiceFromExpenses(InvoiceFromExpensesRequest request) {
        return TenantContextHolder.required().flatMap(ctx -> {
            // Step 1: load candidates
            Mono<List<Expense>> candidatesMono;
            if (request.expenseIds() != null && !request.expenseIds().isEmpty()) {
                candidatesMono = Flux.fromIterable(request.expenseIds())
                        .flatMap(id -> expenses.findByTenantIdAndId(ctx.tenantId(), id))
                        .collectList();
            } else if (request.projectId() != null) {
                candidatesMono = expenses.findAllByTenantIdAndProjectIdAndApprovalStatusAndBillingStatus(
                        ctx.tenantId(), request.projectId(), ApprovalStatus.APPROVED, BillingStatus.UNBILLED)
                        .collectList();
            } else {
                return Mono.error(new DigiPresBeException(
                        "Either projectId or expenseIds must be supplied", 3511, 400));
            }

            return candidatesMono.flatMap(all -> {
                // Filter: APPROVED + billable + UNBILLED
                List<Expense> candidates = all.stream()
                        .filter(e -> e.getApprovalStatus() == ApprovalStatus.APPROVED
                                && e.isBillable()
                                && e.getBillingStatus() == BillingStatus.UNBILLED)
                        .toList();

                // Explicit isEmpty check — NOT switchIfEmpty (§9 invariant)
                if (candidates.isEmpty()) {
                    boolean allInvoiced = !all.isEmpty() && all.stream()
                            .allMatch(e -> e.getBillingStatus() == BillingStatus.INVOICED);
                    if (allInvoiced) {
                        return Mono.error(new DigiPresBeException(
                                "All selected expenses are already invoiced", 3532, 409));
                    }
                    return Mono.error(new DigiPresBeException(
                            "No eligible (approved, billable, unbilled) expenses to invoice", 3530, 409));
                }

                // Step 2: build line items with markup
                List<LineItem> lineItems = buildLineItems(candidates, request.defaultMarkupPercent());

                // Step 3: resolve invoice header
                Mono<InvoiceHeader> headerMono;
                if (request.projectId() != null
                        && (request.contactId() == null && request.companyId() == null && request.dealId() == null)) {
                    headerMono = projects.findByTenantIdAndId(ctx.tenantId(), request.projectId())
                            .map(p -> new InvoiceHeader(p.getPrimaryContactId(), p.getCompanyId(),
                                    p.getDealId(), request.projectId()))
                            .switchIfEmpty(Mono.just(new InvoiceHeader(null, null, null, request.projectId())));
                } else {
                    headerMono = Mono.just(new InvoiceHeader(
                            request.contactId(), request.companyId(), request.dealId(), request.projectId()));
                }

                return headerMono.flatMap(header -> {
                    Invoice invoice = Invoice.builder()
                            .tenantId(ctx.tenantId())
                            .projectId(header.projectId())
                            .contactId(header.contactId())
                            .companyId(header.companyId())
                            .dealId(header.dealId())
                            .currency("USD")
                            .lineItems(lineItems)
                            .status(Invoice.Status.DRAFT)
                            .build();

                    // Step 4: create DRAFT invoice via the existing InvoiceService.create
                    // (no INVOICE_FINALIZED emitted — only the DRAFT→SENT edge does that)
                    return invoiceService.create(invoice)
                            .flatMap(created -> {
                                // Step 5: mark all source expenses INVOICED (durable idempotency anchor)
                                UUID invoiceId = created.getId();
                                List<Expense> toSave = candidates.stream()
                                        .map(e -> e.toBuilder()
                                                .billingStatus(BillingStatus.INVOICED)
                                                .invoicedInvoiceId(invoiceId)
                                                .build())
                                        .toList();
                                return expenses.saveAll(toSave).collectList()
                                        .flatMap(saved -> {
                                            Map<String, Object> payload = new HashMap<>();
                                            payload.put("invoiceId", invoiceId.toString());
                                            payload.put("expenseCount", candidates.size());
                                            events.publish(DomainEvent.of(
                                                    DomainEventType.EXPENSE_INVOICED,
                                                    ctx.tenantId(), invoiceId, payload));
                                            return Mono.just(created);
                                        });
                            });
                });
            });
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds {@link LineItem}s from eligible expenses with markup applied (D-D8).
     * Per-expense markup precedence: {@code expense.markupPercent} → {@code defaultMarkupPercent}
     * → {@code BigDecimal.ZERO}.
     */
    private List<LineItem> buildLineItems(List<Expense> candidates, BigDecimal defaultMarkup) {
        List<LineItem> lines = new ArrayList<>();
        for (Expense e : candidates) {
            BigDecimal markup = e.getMarkupPercent() != null ? e.getMarkupPercent()
                    : (defaultMarkup != null ? defaultMarkup : BigDecimal.ZERO);
            // unitPrice = amount × (1 + markup/100)
            BigDecimal factor = BigDecimal.ONE.add(markup.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            BigDecimal unitPrice = e.getAmount().multiply(factor).setScale(2, RoundingMode.HALF_UP);

            String desc = "Expense — " + e.getDescription()
                    + (e.getCategory() != null ? " (" + e.getCategory() + ")" : "");

            lines.add(LineItem.builder()
                    .description(desc)
                    .quantity(BigDecimal.ONE)
                    .unitPrice(unitPrice)
                    .discountPercent(BigDecimal.ZERO)
                    .taxPercent(BigDecimal.ZERO)
                    .build());
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // Nested record types
    // -------------------------------------------------------------------------

    /**
     * Request payload for {@link #createInvoiceFromExpenses}.
     * Either {@code projectId} or {@code expenseIds} must be supplied.
     */
    public record InvoiceFromExpensesRequest(
            UUID projectId,
            List<UUID> expenseIds,
            UUID contactId,
            UUID companyId,
            UUID dealId,
            BigDecimal defaultMarkupPercent
    ) {}

    /** Resolved invoice header context. */
    private record InvoiceHeader(UUID contactId, UUID companyId, UUID dealId, UUID projectId) {}
}
