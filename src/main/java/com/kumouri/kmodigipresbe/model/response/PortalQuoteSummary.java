package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.quote.Quote;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Portal-facing quote projection (Phase G — G-D1).
 *
 * <p>Omits {@code tenantId}, {@code version}, {@code dealId}, {@code contactId},
 * {@code companyId}, {@code priceListId}, {@code lineItems}, {@code notes},
 * {@code terms}, {@code statusChangedAt}, {@code pdfStorageRef}, {@code customFields},
 * and timestamps — these are either staff-internal or not needed by the portal UI.
 * Client-visible fields only.
 */
public record PortalQuoteSummary(
        String id,
        String quoteNumber,
        Quote.Status status,
        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        LocalDate issuedAt,
        LocalDate expiresAt) {

    public static PortalQuoteSummary from(Quote quote) {
        return new PortalQuoteSummary(
                quote.getId() == null ? null : quote.getId().toString(),
                quote.getQuoteNumber(),
                quote.getStatus(),
                quote.getCurrency(),
                quote.getSubtotal(),
                quote.getDiscountTotal(),
                quote.getTaxTotal(),
                quote.getTotal(),
                quote.getIssuedAt(),
                quote.getExpiresAt());
    }
}
