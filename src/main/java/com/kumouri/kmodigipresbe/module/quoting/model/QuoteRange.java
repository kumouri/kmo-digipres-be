package com.kumouri.kmodigipresbe.module.quoting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * T8 (Home Services "QuoteNow") — <strong>the product</strong>: a defensible price RANGE
 * (low–high), never a single number. Synthesized by {@code QuoteSynthesisService} from the
 * homeowner's {@link QuoteAttributes} against the tenant's {@link PriceBook}.
 *
 * <h2>The wrong-number-liability fence — the estimate disclaimer</h2>
 * EVERY {@code QuoteRange} carries a non-blank {@link #estimateDisclaimer}. It is set centrally in
 * {@code QuoteSynthesisService} (never by a caller) so it cannot be omitted — the homeowner always
 * sees "this is an estimate; the final price is confirmed after an on-site inspection." A
 * release-blocking IT asserts every synthesized range carries it.
 *
 * @param low               the low end of the estimate (inclusive)
 * @param high              the high end of the estimate (inclusive; {@code >= low})
 * @param currency          ISO-4217 (e.g. "USD")
 * @param basis             which price-book line item / job kind produced the range (e.g.
 *                          "condenser — replace", or "diagnostic visit"); for the office to audit
 * @param estimateDisclaimer the mandatory "estimate, final price after on-site inspection" copy
 * @param diagnosticOnly    true when no priceable line matched and this is a flat diagnostic-visit
 *                          fee range (the quote degrades gracefully rather than failing)
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRange {

    private BigDecimal low;
    private BigDecimal high;

    @Builder.Default
    private String currency = "USD";

    private String basis;

    private String estimateDisclaimer;

    @Builder.Default
    private boolean diagnosticOnly = false;
}
