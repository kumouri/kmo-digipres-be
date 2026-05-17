package com.kumouri.kmodigipresbe.service.billing;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Debug accounting-push round-trip (Phase E — E-D10). The
 * {@code INVOICE_FINALIZED → QuickBooksInvoiceSync} hook is <strong>already
 * shipped</strong>; Phase E does NOT rebuild or modify it. This thin service only
 * <em>reaches</em> that seam on demand:
 * <ul>
 *   <li>a DRAFT invoice → {@code invoiceService.setStatus(id, SENT)} (the
 *       <strong>unchanged</strong> service method) fires the DRAFT→SENT edge →
 *       {@code INVOICE_FINALIZED} → the shipped {@code QuickBooksInvoiceSync};</li>
 *   <li>an already-SENT invoice → re-publish {@code INVOICE_FINALIZED} directly
 *       (the {@code setStatus} edge guard would suppress a no-op SENT→SENT) to
 *       force a re-sync. The payload mirrors
 *       {@code InvoiceService.maybePublishFinalized} verbatim.</li>
 * </ul>
 * Naturally idempotent end-to-end: {@code QuickBooksInvoiceSync.alreadySynced}
 * makes a re-push a no-op via {@code Invoice.externalRefs["quickbooks"]}.
 *
 * <p>Does NOT modify {@code QuickBooksInvoiceSync} or
 * {@code InvoiceService.setStatus} (§7 non-goal). Error {@code 3630} is a
 * defensive not-eligible guard (e.g. VOIDED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingPushService {

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final DomainEventPublisher events;

    public Mono<Invoice> push(UUID invoiceId) {
        return invoiceService.findById(invoiceId).flatMap(inv -> {
            Invoice.Status status = inv.getStatus();
            if (status == Invoice.Status.VOIDED) {
                return Mono.error(new DigiPresBeException(
                        "Invoice is VOIDED — not eligible for accounting push", 3630, 409));
            }
            if (status == Invoice.Status.DRAFT) {
                // DRAFT→SENT via the UNCHANGED service method fires INVOICE_FINALIZED.
                return invoiceService.setStatus(invoiceId, Invoice.Status.SENT);
            }
            // Already SENT/PARTIALLY_PAID/PAID/OVERDUE — the setStatus edge guard
            // would suppress a no-op transition, so re-publish INVOICE_FINALIZED
            // directly to force a re-sync (QuickBooksInvoiceSync.alreadySynced makes
            // it a no-op if it was already pushed — naturally idempotent).
            return republishFinalized(inv).thenReturn(inv);
        });
    }

    private Mono<Void> republishFinalized(Invoice inv) {
        return TenantContextHolder.required().flatMap(ctx -> {
            // Payload mirrors InvoiceService.maybePublishFinalized verbatim.
            Map<String, Object> payload = new HashMap<>();
            payload.put("invoiceId", inv.getId() == null ? null : inv.getId().toString());
            payload.put("totalAmount", inv.getTotal());
            payload.put("contactId", inv.getContactId() == null
                    ? null : inv.getContactId().toString());
            payload.put("currency", inv.getCurrency());
            events.publish(DomainEvent.of(
                    DomainEventType.INVOICE_FINALIZED,
                    inv.getTenantId() != null ? inv.getTenantId() : ctx.tenantId(),
                    inv.getId(), payload));
            log.debug("Re-published INVOICE_FINALIZED for already-SENT invoice {} "
                    + "(accounting-push force re-sync)", inv.getId());
            return Mono.empty();
        });
    }
}
