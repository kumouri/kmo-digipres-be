package com.kumouri.kmodigipresbe.service.report;

import com.kumouri.kmodigipresbe.automation.RuleCondition;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.report.AggregationOp;
import com.kumouri.kmodigipresbe.model.report.AggregationSpec;
import com.kumouri.kmodigipresbe.model.report.SavedReport;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Executes a {@link SavedReport} as a Mongo aggregation pipeline:
 * <ol>
 *   <li>{@code $match} on {@code tenantId} (always) plus translated
 *       {@code filterTree}.</li>
 *   <li>{@code $group} by {@code groupBy} fields with each {@link AggregationSpec}
 *       producing one output column.</li>
 * </ol>
 *
 * <p>Output is a list of {@code Map<String, Object>}: one row per group, with
 * keys for each group-by field and each aggregation's {@code outputName}.
 *
 * <p>Filter translation reuses the Phase 9d-promoted {@code ConditionEvaluator}
 * semantics; for the Mongo {@code $match} stage we map each
 * {@link RuleCondition} to a {@link Criteria} predicate directly so the database
 * does the work rather than streaming everything in-memory.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRunner {

    private final ReactiveMongoTemplate mongo;

    public Mono<List<java.util.Map<String, Object>>> run(SavedReport report) {
        return TenantContextHolder.required()
                .flatMap(ctx -> runForTenant(report, ctx.tenantId()));
    }

    /**
     * Tenant-explicit run path used by the scheduler. Tenant context isn't
     * established yet at scheduler-tick time; the caller supplies it.
     */
    public Mono<List<java.util.Map<String, Object>>> runForTenant(SavedReport report, UUID tenantId) {
        if (report.getEntityType() == null || report.getEntityType().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "SavedReport.entityType is required", 2200, 400));
        }
        Criteria match = Criteria.where("tenantId").is(tenantId);
        for (RuleCondition c : nullSafe(report.getFilterTree())) {
            Criteria sub = translate(c);
            if (sub != null) match = match.andOperator(sub);
        }
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.match(match));

        // Coerce numeric aggregation fields to double before $group. Spring Data
        // MongoDB stores BigDecimal as a String by default (e.g. Deal.value = "5000"),
        // and Mongo's $sum / $avg / $min / $max ignore non-numeric inputs and return
        // 0. The $addFields + $convert stage converts each field referenced by a
        // numeric aggregation to a double (or 0 when missing / unparseable).
        Set<String> numericFields = numericFieldsFor(report.getAggregations());
        if (!numericFields.isEmpty()) {
            Document setStage = new Document();
            for (String f : numericFields) {
                setStage.put(f, new Document("$convert", new Document()
                        .append("input", "$" + f)
                        .append("to", "double")
                        .append("onError", 0)
                        .append("onNull", 0)));
            }
            ops.add(ctx -> new Document("$addFields", setStage));
        }

        GroupOperation group = buildGroup(report.getGroupBy(), report.getAggregations());
        ops.add(group);

        Aggregation agg = Aggregation.newAggregation(ops);
        return mongo.aggregate(agg, report.getEntityType(), Document.class)
                .map(d -> projectRow(d, report))
                .collectList();
    }

    private GroupOperation buildGroup(List<String> groupBy, List<AggregationSpec> aggs) {
        List<String> keys = nullSafe(groupBy);
        GroupOperation group = keys.isEmpty()
                ? Aggregation.group()
                : Aggregation.group(keys.toArray(new String[0]));
        for (AggregationSpec spec : nullSafe(aggs)) {
            String out = (spec.getOutputName() == null || spec.getOutputName().isBlank())
                    ? defaultOutputName(spec)
                    : spec.getOutputName();
            AggregationOp op = spec.getOp() == null ? AggregationOp.COUNT : spec.getOp();
            group = switch (op) {
                case COUNT -> group.count().as(out);
                case SUM -> group.sum(spec.getField()).as(out);
                case AVG -> group.avg(spec.getField()).as(out);
                case MIN -> group.min(spec.getField()).as(out);
                case MAX -> group.max(spec.getField()).as(out);
            };
        }
        return group;
    }

    private java.util.Map<String, Object> projectRow(Document doc, SavedReport report) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        List<String> keys = nullSafe(report.getGroupBy());
        Object id = doc.get("_id");
        if (keys.size() == 1) {
            out.put(keys.get(0), id);
        } else if (id instanceof Document idDoc) {
            for (String k : keys) out.put(k, idDoc.get(k));
        }
        for (AggregationSpec spec : nullSafe(report.getAggregations())) {
            String name = (spec.getOutputName() == null || spec.getOutputName().isBlank())
                    ? defaultOutputName(spec)
                    : spec.getOutputName();
            out.put(name, doc.get(name));
        }
        return out;
    }

    private static String defaultOutputName(AggregationSpec spec) {
        String field = spec.getField() == null ? "_" : spec.getField();
        return (spec.getOp() == null ? "COUNT" : spec.getOp().name().toLowerCase()) + "_" + field;
    }

    /**
     * Translate one RuleCondition to a Mongo Criteria predicate. Mirrors the
     * Phase 9d ConditionEvaluator's op semantics; null = skip (treat as no-op).
     */
    static Criteria translate(RuleCondition c) {
        if (c == null || c.getField() == null || c.getOp() == null) return null;
        return switch (c.getOp()) {
            case EQUALS -> Criteria.where(c.getField()).is(c.getValue());
            case NOT_EQUALS -> Criteria.where(c.getField()).ne(c.getValue());
            case EXISTS -> Criteria.where(c.getField()).ne(null);
            case NOT_EXISTS -> Criteria.where(c.getField()).is(null);
            case GREATER_THAN -> Criteria.where(c.getField()).gt(c.getValue());
            case LESS_THAN -> Criteria.where(c.getField()).lt(c.getValue());
        };
    }

    private static <T> List<T> nullSafe(List<T> in) {
        return in == null ? List.of() : in;
    }

    private static Set<String> numericFieldsFor(List<AggregationSpec> aggs) {
        if (aggs == null) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (AggregationSpec spec : aggs) {
            if (spec == null || spec.getField() == null || spec.getField().isBlank()) continue;
            AggregationOp op = spec.getOp();
            if (op == null) continue;
            switch (op) {
                case SUM, AVG, MIN, MAX -> out.add(spec.getField());
                case COUNT -> { /* not a numeric agg */ }
            }
        }
        // {@code _id} is the record id (BSON Binary); never coerce.
        out.remove("_id");
        return out;
    }
}
