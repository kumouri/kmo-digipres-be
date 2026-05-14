package com.kumouri.kmodigipresbe.model.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One aggregation column on a {@link SavedReport}. {@code field} is the source
 * document path (e.g. {@code "value"}); {@code op} is the aggregation, and
 * {@code outputName} controls the result column header.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AggregationSpec {
    private String field;
    private AggregationOp op;
    /** Output column name. Defaults to "{op}_{field}" if blank. */
    private String outputName;
}
