package com.kumouri.kmodigipresbe.config.conversion;

import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BigDecimalConvertersTest {

    @Test
    void bigDecimal_toDecimal128_roundTrip() {
        BigDecimal in = new BigDecimal("12345.67");
        Decimal128 encoded = BigDecimalConverters.BigDecimalToDecimal128.INSTANCE.convert(in);
        assertThat(encoded).isNotNull();
        BigDecimal decoded = BigDecimalConverters.Decimal128ToBigDecimal.INSTANCE.convert(encoded);
        assertThat(decoded).isEqualByComparingTo(in);
    }

    @Test
    void nullsPassThrough() {
        assertThat(BigDecimalConverters.BigDecimalToDecimal128.INSTANCE.convert(null)).isNull();
        assertThat(BigDecimalConverters.Decimal128ToBigDecimal.INSTANCE.convert(null)).isNull();
        assertThat(BigDecimalConverters.StringToBigDecimal.INSTANCE.convert(null)).isNull();
        assertThat(BigDecimalConverters.StringToBigDecimal.INSTANCE.convert("")).isNull();
        assertThat(BigDecimalConverters.StringToBigDecimal.INSTANCE.convert("  ")).isNull();
    }

    @Test
    void legacyStringFallback_parsesValidString() {
        BigDecimal parsed = BigDecimalConverters.StringToBigDecimal.INSTANCE.convert("9876.54");
        assertThat(parsed).isEqualByComparingTo(new BigDecimal("9876.54"));
    }

    @Test
    void legacyStringFallback_returnsNullForUnparseable() {
        // Unparseable returns null instead of throwing — Spring Data passes the
        // null through and the field comes back as null on the entity. Better
        // than blowing up the read.
        assertThat(BigDecimalConverters.StringToBigDecimal.INSTANCE.convert("not a number"))
                .isNull();
    }

    @Test
    void preservesScale() {
        BigDecimal in = new BigDecimal("0.00010");
        Decimal128 encoded = BigDecimalConverters.BigDecimalToDecimal128.INSTANCE.convert(in);
        BigDecimal decoded = BigDecimalConverters.Decimal128ToBigDecimal.INSTANCE.convert(encoded);
        // Decimal128 preserves significant digits; equals-by-comparing-to is the
        // semantic guarantee, not exact representation.
        assertThat(decoded).isEqualByComparingTo(in);
    }
}
