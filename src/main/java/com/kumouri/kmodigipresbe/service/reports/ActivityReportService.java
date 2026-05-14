package com.kumouri.kmodigipresbe.service.reports;

import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Activity counts grouped by type inside a window. Useful for "how busy was last
 * week" style dashboards.
 */
@Service
@RequiredArgsConstructor
public class ActivityReportService {

    private final ReactiveMongoOperations mongo;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TypeRow {
        private String type;
        private long count;
    }

    public Mono<List<TypeRow>> report(Instant from, Instant to) {
        return TenantContextHolder.required().flatMap(ctx -> {
            Aggregation agg = newAggregation(
                    match(where("tenantId").is(ctx.tenantId())
                            .and("occurredAt").gte(from).lt(to)),
                    group("type").count().as("count"));
            return mongo.aggregate(agg, "activities", CountDoc.class)
                    .collectList()
                    .map(this::project);
        });
    }

    private List<TypeRow> project(List<CountDoc> docs) {
        Map<String, Long> byType = new HashMap<>();
        for (CountDoc d : docs) byType.put(d.id, d.count);
        List<TypeRow> out = new ArrayList<>();
        for (ActivityType t : ActivityType.values()) {
            out.add(new TypeRow(t.name(), byType.getOrDefault(t.name(), 0L)));
        }
        return out;
    }

    @SuppressWarnings("unused")
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CountDoc {
        private String id;
        private long count;
    }
}
