package com.kumouri.kmodigipresbe.config.conversion;

import org.bson.types.Decimal128;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

import java.math.BigDecimal;

/**
 * Mongo conversion for {@link BigDecimal} fields.
 *
 * <h2>Why this exists</h2>
 * Spring Data MongoDB 4.x defaults to persisting {@code BigDecimal} as a BSON
 * <em>String</em>. That stored shape works for trivial reads but breaks
 * aggregation pipelines: {@code $sum / $avg / $min / $max} ignore non-numeric
 * inputs and return {@code 0} for any group containing only string values.
 * Phase 9e's {@code ReportRunner} surfaced this with the
 * "deals by stage, sum of value" report returning {@code totalValue=0}.
 *
 * <h2>Fix</h2>
 * Register a {@link WritingConverter} that maps {@code BigDecimal} to BSON
 * {@link Decimal128}, plus a {@link ReadingConverter} that maps it back. A
 * second {@code ReadingConverter} parses any pre-existing String-stored values
 * so reads keep working before a backfill is run.
 *
 * <p>Wired into the converter chain by {@code MongoConfig.customConversions()}.
 * Phase 9e's {@code ReportRunner.$convert(to: "double", onError: 0)} stage
 * stays as defense-in-depth: it handles legacy String-stored data the
 * aggregation pipeline sees BEFORE a backfill rewrites old docs.
 */
public final class BigDecimalConverters {

    private BigDecimalConverters() {
    }

    @WritingConverter
    public enum BigDecimalToDecimal128 implements Converter<BigDecimal, Decimal128> {
        INSTANCE;

        @Override
        public Decimal128 convert(BigDecimal source) {
            return source == null ? null : new Decimal128(source);
        }
    }

    @ReadingConverter
    public enum Decimal128ToBigDecimal implements Converter<Decimal128, BigDecimal> {
        INSTANCE;

        @Override
        public BigDecimal convert(Decimal128 source) {
            return source == null ? null : source.bigDecimalValue();
        }
    }

    /**
     * Legacy-data fallback: existing docs persisted before this PR have BigDecimal
     * fields stored as Strings. This reading converter parses them on read so the
     * cutover doesn't require a data migration first.
     *
     * <p>Spring Data picks this converter by source BSON type. Documents with
     * Decimal128 fields use {@link Decimal128ToBigDecimal}; documents with String
     * fields use this one.
     */
    @ReadingConverter
    public enum StringToBigDecimal implements Converter<String, BigDecimal> {
        INSTANCE;

        @Override
        public BigDecimal convert(String source) {
            if (source == null || source.isBlank()) return null;
            try {
                return new BigDecimal(source);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
