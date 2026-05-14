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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final QuoteRepository quotes;
    private final DomainEventPublisher events;

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
                    return invoices.save(inv)
                            .doOnNext(saved -> maybePublishFinalized(
                                    saved, Invoice.Status.DRAFT, saved.getStatus()));
                });
    }

    public Mono<Invoice> setStatus(UUID id, Invoice.Status target) {
        return findById(id).flatMap(inv -> {
            Invoice.Status previous = inv.getStatus();
            inv.setStatus(target);
            inv.setStatusChangedAt(Instant.now());
            return invoices.save(inv)
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
                return invoices.save(inv);
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
