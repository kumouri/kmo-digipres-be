package com.kumouri.kmodigipresbe.service.billing;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.PaymentRepository;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final QuoteRepository quotes;
    private final DomainEventPublisher events;
    private final InvoiceNumberGenerator invoiceNumberGenerator;

    /**
     * The "issued / terminal" states. The first transition OUT of {@code DRAFT}
     * into any of these is the conceptual finalize edge — the point at which an
     * invoice number is assigned (Phase E — the user-authorized resolution of the
     * escalated {@code tenant_number_idx} blocker; an explicitly user-authorized
     * override of plan §7's "numbering is a non-goal" — the chosen policy requires
     * it). {@code VOIDED} is deliberately excluded: a voided invoice was never
     * issued, so it never burns a number.
     */
    private static final Set<Invoice.Status> ISSUED_STATES = EnumSet.of(
            Invoice.Status.SENT,
            Invoice.Status.PARTIALLY_PAID,
            Invoice.Status.PAID,
            Invoice.Status.OVERDUE);

    public Flux<Invoice> findAll() {
        return invoices.findAll();
    }

    public Mono<Invoice> findById(UUID id) {
        return invoices.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Invoice not found", 2300, 404)))
                .flatMap(this::withBalance);
    }

    public Mono<Invoice> create(Invoice toCreate) {
        toCreate.setId(null);
        toCreate.setStatusChangedAt(Instant.now());
        recomputeTotalsFromLines(toCreate);
        return invoices.save(toCreate);
    }

    /**
     * Generate an Invoice from an accepted {@code Quote}. Line items are deep-copied
     * so subsequent quote edits don't change the invoice.
     */
    public Mono<Invoice> createFromQuote(UUID quoteId) {
        return quotes.findById(quoteId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Quote not found", 2310, 404)))
                .flatMap(q -> {
                    Invoice inv = Invoice.builder()
                            .quoteId(q.getId())
                            .dealId(q.getDealId())
                            .contactId(q.getContactId())
                            .companyId(q.getCompanyId())
                            .currency(q.getCurrency())
                            .lineItems(q.getLineItems().stream().map(li -> li.toBuilder().build()).toList())
                            .subtotal(q.getSubtotal())
                            .discountTotal(q.getDiscountTotal())
                            .taxTotal(q.getTaxTotal())
                            .total(q.getTotal())
                            .issuedAt(LocalDate.now())
                            .dueAt(LocalDate.now().plusDays(30))
                            .status(Invoice.Status.SENT)
                            .statusChangedAt(Instant.now())
                            .build();
                    // createFromQuote issues directly as SENT (it was never a DRAFT)
                    // with a null number — assign one at this issued edge too, via the
                    // same explicit-boolean helper (idempotent; a caller-supplied
                    // number would be kept, but this path never sets one).
                    return assignNumberIfIssued(inv, inv.getStatus())
                            .flatMap(this::saveWithNumberRetry)
                            .doOnNext(saved -> maybePublishFinalized(
                                    saved, Invoice.Status.DRAFT, saved.getStatus()));
                });
    }

    public Mono<Invoice> setStatus(UUID id, Invoice.Status target) {
        return findById(id).flatMap(inv -> {
            Invoice.Status previous = inv.getStatus();
            inv.setStatus(target);
            inv.setStatusChangedAt(Instant.now());
            // Finalize-time numbering (Phase E blocker resolution): assign a number
            // at the first transition OUT of DRAFT into an issued/terminal state,
            // BEFORE the status save. Explicit-boolean idempotent — a re-finalize /
            // VOID→SENT / DRAFT→PAID auto-advance never regenerates an existing number.
            return assignNumberIfIssued(inv, target)
                    .flatMap(this::saveWithNumberRetry)
                    .doOnNext(saved -> maybePublishFinalized(saved, previous, target));
        });
    }

    /**
     * Phase 10d — when an Invoice transitions out of {@code DRAFT} into {@code SENT}
     * (the conceptual "finalize" step), broadcast a {@link DomainEventType#INVOICE_FINALIZED}
     * so downstream integrations (QuickBooks invoice sync, custom workflow rules)
     * can react. Idempotent: re-saving with the same status does nothing; only the
     * actual DRAFT→SENT edge fires the event.
     */
    private void maybePublishFinalized(Invoice saved, Invoice.Status previous, Invoice.Status target) {
        if (target != Invoice.Status.SENT) return;
        if (previous == Invoice.Status.SENT) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", saved.getId() == null ? null : saved.getId().toString());
        payload.put("totalAmount", saved.getTotal());
        payload.put("contactId", saved.getContactId() == null ? null : saved.getContactId().toString());
        payload.put("currency", saved.getCurrency());
        events.publish(DomainEvent.of(
                DomainEventType.INVOICE_FINALIZED, saved.getTenantId(), saved.getId(), payload));
    }

    /**
     * Finalize-time invoice numbering (Phase E — the user-authorized resolution of
     * the escalated {@code Invoice.tenant_number_idx} blocker; an explicitly
     * user-authorized override of plan §7's "numbering is a non-goal", recorded as
     * an authorized scope change). Format {@code INV-{YYYY}-{NNNN}}, per-(tenant,
     * year), gapless by issue, January reset (all via {@link InvoiceNumberGenerator}).
     *
     * <p><strong>Explicit-boolean idempotency (the §9 discipline):</strong> a number
     * is assigned <em>only</em> when {@code target} is an issued/terminal state AND
     * {@code invoice.getInvoiceNumber() == null}. An invoice that already has a
     * number keeps it — a re-finalize, a {@code VOIDED→SENT}, or a
     * {@code DRAFT→PAID} payment auto-advance must NOT burn a second number. This is
     * a plain {@code if (number == null) assign else keep} branch — deliberately
     * NOT a {@code switchIfEmpty} around the assignment (the documented reactive
     * trap). DRAFT (and the never-issued VOIDED) stay {@code invoiceNumber == null},
     * which is exactly why {@code tenant_number_idx} is migrated to a
     * <em>partial</em> unique index ({@code InvoiceNumberIndexInitializer}).
     *
     * <p>Backstop: the partial unique index is the correctness guarantee — if a race
     * ever slips a duplicate through (it shouldn't, the counter is atomic), the save
     * throws {@code DuplicateKeyException} and we retry once with a fresh increment,
     * mirroring {@code ProjectService.generateCodeAndSave} exactly. (The retry
     * surfaces via the caller's {@code invoices.save}; this helper only mints +
     * sets the number — the per-save retry is wired where the save happens.)
     *
     * @return the same invoice instance (number set iff issued + previously null)
     */
    private Mono<Invoice> assignNumberIfIssued(Invoice invoice, Invoice.Status target) {
        if (invoice.getInvoiceNumber() == null && ISSUED_STATES.contains(target)) {
            return invoiceNumberGenerator.next(invoice.getTenantId())
                    .map(number -> {
                        invoice.setInvoiceNumber(number);
                        return invoice;
                    });
        }
        // Already numbered, or not an issued transition — keep as-is. Never
        // regenerate; never switchIfEmpty around the assignment.
        return Mono.just(invoice);
    }

    /**
     * Persists the (possibly just-numbered) invoice with a single defensive retry
     * on a {@code tenant_number_idx} unique-index collision — a fresh number is
     * minted and the save re-attempted exactly once (the
     * {@code ProjectService.generateCodeAndSave} C-D3 pattern). A second collision
     * is a genuine fault → 3640 (defensive; the atomic counter makes this
     * unreachable in practice).
     */
    private Mono<Invoice> saveWithNumberRetry(Invoice invoice) {
        return invoices.save(invoice)
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    // Only a numbered invoice can collide on tenant_number_idx (the
                    // index is partial — null-number DRAFTs never collide). If the
                    // failing invoice has no number this is an unrelated unique-index
                    // violation: re-raise, don't mask it by minting a spurious number.
                    if (invoice.getInvoiceNumber() == null) {
                        return Mono.error(ex);
                    }
                    return invoiceNumberGenerator.next(invoice.getTenantId())
                            .flatMap(retryNumber -> {
                                invoice.setInvoiceNumber(retryNumber);
                                return invoices.save(invoice);
                            })
                            .onErrorMap(DuplicateKeyException.class, e ->
                                    new DigiPresBeException(
                                            "Invoice number generation failed after retry",
                                            3640, 500));
                });
    }

    public Mono<Void> delete(UUID id) {
        return invoices.deleteById(id);
    }

    public Mono<Payment> recordPayment(Payment toCreate) {
        toCreate.setId(null);
        if (toCreate.getPaidAt() == null) toCreate.setPaidAt(Instant.now());
        return payments.save(toCreate)
                .flatMap(saved -> refreshInvoiceStatus(saved.getInvoiceId()).thenReturn(saved));
    }

    public Flux<Payment> paymentsFor(UUID invoiceId) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                payments.findAllByTenantIdAndInvoiceId(ctx.tenantId(), invoiceId));
    }

    private Mono<Invoice> withBalance(Invoice inv) {
        return paymentsFor(inv.getId())
                .reduce(BigDecimal.ZERO, (acc, p) -> acc.add(p.getAmount() == null ? BigDecimal.ZERO : p.getAmount()))
                .map(paid -> {
                    BigDecimal balance = (inv.getTotal() == null ? BigDecimal.ZERO : inv.getTotal())
                            .subtract(paid)
                            .setScale(2, RoundingMode.HALF_UP);
                    inv.setBalance(balance);
                    return inv;
                });
    }

    private Mono<Invoice> refreshInvoiceStatus(UUID invoiceId) {
        return findById(invoiceId).flatMap(inv -> {
            BigDecimal balance = inv.getBalance() == null ? BigDecimal.ZERO : inv.getBalance();
            Invoice.Status target;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) target = Invoice.Status.PAID;
            else if (balance.compareTo(inv.getTotal()) < 0) target = Invoice.Status.PARTIALLY_PAID;
            else target = inv.getStatus();
            if (target != inv.getStatus()) {
                inv.setStatus(target);
                inv.setStatusChangedAt(Instant.now());
                // A payment can auto-advance a DRAFT invoice straight to
                // PARTIALLY_PAID/PAID without an explicit setStatus — that is still
                // a first transition OUT of DRAFT, so number it here too (same
                // explicit-boolean helper; never regenerates an existing number).
                return assignNumberIfIssued(inv, target).flatMap(this::saveWithNumberRetry);
            }
            return Mono.just(inv);
        });
    }

    private void recomputeTotalsFromLines(Invoice inv) {
        // Reuse Quote.computeTotals via a synthetic conversion — keeps the math in
        // one place. Cheap object alloc for the win-vs-duplication trade.
        Quote shim = Quote.builder()
                .lineItems(inv.getLineItems())
                .build();
        shim.computeTotals();
        inv.setLineItems(shim.getLineItems());
        inv.setSubtotal(shim.getSubtotal());
        inv.setDiscountTotal(shim.getDiscountTotal());
        inv.setTaxTotal(shim.getTaxTotal());
        inv.setTotal(shim.getTotal());
    }
}
