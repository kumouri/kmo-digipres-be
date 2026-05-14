package com.kumouri.kmodigipresbe.model.quote;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteTotalsTest {

    @Test
    void emptyQuote_totalsAreZero() {
        Quote q = Quote.builder().lineItems(List.of()).build();
        q.computeTotals();
        assertThat(q.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(q.getDiscountTotal()).isEqualByComparingTo("0.00");
        assertThat(q.getTaxTotal()).isEqualByComparingTo("0.00");
        assertThat(q.getTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void singleLine_appliesDiscountThenTax() {
        // gross = 10 * 100 = 1000; discount 10% = 100; afterDiscount = 900;
        // tax 8% on 900 = 72; lineTotal = 972; total = 972
        LineItem li = LineItem.builder()
                .quantity(new BigDecimal("10"))
                .unitPrice(new BigDecimal("100"))
                .discountPercent(new BigDecimal("10"))
                .taxPercent(new BigDecimal("8"))
                .build();
        Quote q = Quote.builder().lineItems(List.of(li)).build();
        q.computeTotals();

        assertThat(q.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(q.getDiscountTotal()).isEqualByComparingTo("100.00");
        assertThat(q.getTaxTotal()).isEqualByComparingTo("72.00");
        assertThat(q.getTotal()).isEqualByComparingTo("972.00");
        assertThat(li.getLineTotal()).isEqualByComparingTo("972.00");
    }

    @Test
    void multipleLines_sumCorrectly() {
        LineItem a = LineItem.builder()
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("50"))
                .build(); // gross 100, no discount/tax, line 100
        LineItem b = LineItem.builder()
                .quantity(new BigDecimal("3"))
                .unitPrice(new BigDecimal("80"))
                .discountPercent(new BigDecimal("25"))
                .build(); // gross 240, discount 60, line 180
        Quote q = Quote.builder().lineItems(List.of(a, b)).build();
        q.computeTotals();

        assertThat(q.getSubtotal()).isEqualByComparingTo("340.00");
        assertThat(q.getDiscountTotal()).isEqualByComparingTo("60.00");
        assertThat(q.getTaxTotal()).isEqualByComparingTo("0.00");
        assertThat(q.getTotal()).isEqualByComparingTo("280.00");
    }

    @Test
    void nullsBecomeZero() {
        LineItem li = LineItem.builder()
                .quantity(new BigDecimal("5"))
                .unitPrice(new BigDecimal("20"))
                .build();
        Quote q = Quote.builder().lineItems(List.of(li)).build();
        q.computeTotals();
        assertThat(q.getTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void recomputeIsIdempotent() {
        LineItem li = LineItem.builder()
                .quantity(new BigDecimal("4"))
                .unitPrice(new BigDecimal("25"))
                .taxPercent(new BigDecimal("10"))
                .build();
        Quote q = Quote.builder().lineItems(List.of(li)).build();
        q.computeTotals();
        BigDecimal firstTotal = q.getTotal();
        q.computeTotals();
        assertThat(q.getTotal()).isEqualByComparingTo(firstTotal);
    }
}
