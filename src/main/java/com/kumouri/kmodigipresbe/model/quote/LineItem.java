package com.kumouri.kmodigipresbe.model.quote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single billable row on a quote or invoice. {@code unitPrice} is denormalized
 * at quote-creation time so changes to the product catalog do not retroactively
 * change pricing on existing quotes / invoices.
 *
 * <p>{@code lineTotal} is computed by {@link Quote#computeTotals()} and persisted
 * for fast list-view rendering.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LineItem {

    private UUID productId;
    private String sku;
    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal discountPercent;
    private BigDecimal taxPercent;
    private BigDecimal lineTotal;
}
