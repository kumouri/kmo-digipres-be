package com.kumouri.kmodigipresbe.service.reports;

import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Deal pipeline aggregation: count + total value per stage for the current tenant.
 * No filtering window for Phase 4 — every open deal counts.
 */
@Service
@RequiredArgsConstructor
public class PipelineReportService {

    private final ReactiveMongoOperations mongo;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StageRow {
        private String stage;
        private long count;
        private BigDecimal totalValue;
    }

    public Mono<List<StageRow>> report() {
        return TenantContextHolder.required().flatMap(ctx -> {
            Aggregation agg = newAggregation(
                    match(where("tenantId").is(ctx.tenantId())),
                    group("stage")
                            .count().as("count")
                            .sum("value").as("totalValue"));
            return mongo.aggregate(agg, "deals", StageDoc.class)
                    .collectList()
                    .map(this::project);
        });
    }

    private List<StageRow> project(List<StageDoc> docs) {
        Map<PipelineStage, StageRow> rows = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            rows.put(stage, new StageRow(stage.name(), 0L, BigDecimal.ZERO));
        }
        Map<String, StageDoc> byStage = new HashMap<>();
        for (StageDoc d : docs) byStage.put(d.id, d);
        List<StageRow> out = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            StageDoc d = byStage.get(stage.name());
            StageRow row = rows.get(stage);
            if (d != null) {
                row.setCount(d.count);
                row.setTotalValue(d.totalValue == null ? BigDecimal.ZERO : d.totalValue);
            }
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unused") // populated by Mongo
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StageDoc {
        private String id;
        private long count;
        private BigDecimal totalValue;
    }

    @SuppressWarnings("unused")
    private static AggregationResults<StageDoc> unused() { return null; }
}
