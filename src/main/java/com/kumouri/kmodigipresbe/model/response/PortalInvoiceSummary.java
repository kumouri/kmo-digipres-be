package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.billing.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Portal-facing invoice projection. Omits {@code lineItems}, {@code contactId},
 * {@code companyId}, {@code customFields}, {@code quoteId}, {@code dealId},
 * {@code tenantId}, {@code version}, and timestamps — these are either staff-internal
 * or already implied by the fact that the invoice was returned by {@code /portal/me/invoices}.
 */
public record PortalInvoiceSummary(
        String id,
        String invoiceNumber,
        Invoice.Status status,
        String currency,
        BigDecimal total,
        BigDecimal balance,
        LocalDate issuedAt,
        LocalDate dueAt) {

    public static PortalInvoiceSummary from(Invoice inv) {
        return new PortalInvoiceSummary(
                inv.getId() == null ? null : inv.getId().toString(),
                inv.getInvoiceNumber(),
                inv.getStatus(),
                inv.getCurrency(),
                inv.getTotal(),
                inv.getBalance(),
                inv.getIssuedAt(),
                inv.getDueAt());
    }
}
