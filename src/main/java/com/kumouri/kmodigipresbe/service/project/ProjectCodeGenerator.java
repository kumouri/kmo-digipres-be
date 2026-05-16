package com.kumouri.kmodigipresbe.service.project;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Generates monotonically-increasing Project codes in the form {@code PRJ-{year}-{seq:03}}
 * (C-D3). The counter is per-(tenant, year) so January 1st automatically resets the
 * sequence for the new year.
 *
 * <p>Implementation: an atomic MongoDB {@code findAndModify} with {@code $inc + upsert}
 * on a {@code project_code_counters} collection keyed by {@code _id = tenantId:year}.
 * This is the only correct mechanism for a monotonic counter without races or holes:
 * {@code count()+1} is race-prone and non-monotonic after deletes; a version-loop scan
 * requires more round-trips without a stronger guarantee. The
 * {@link com.kumouri.kmodigipresbe.model.project.Project#code} unique compound index
 * is the correctness backstop — if a race ever slips through (it shouldn't), the second
 * save throws {@code DuplicateKeyException} and {@link ProjectService} retries once.
 *
 * <p>The counter document is intentionally NOT a
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped} entity — it is keyed by a
 * composite {@code _id} string, like the Mongo-native counter pattern. It is accessed
 * via {@link ReactiveMongoTemplate} (not a Spring-Data repository) to avoid the
 * auto-tenant-filter that {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository}
 * applies. Year is always UTC (consistent with {@code Instant.now()} elsewhere in the
 * codebase).
 */
@Component
@RequiredArgsConstructor
public class ProjectCodeGenerator {

    private static final String COUNTERS_COLLECTION = "project_code_counters";

    private final ReactiveMongoTemplate mongoTemplate;

    /**
     * Returns the next {@code PRJ-{year}-{seq:03}} code for the given tenant.
     * Atomic: two concurrent calls for the same (tenant, year) always get distinct seq values.
     *
     * @param tenantId the owning tenant
     * @return e.g. {@code "PRJ-2026-001"}
     */
    public Mono<String> next(UUID tenantId) {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String id = tenantId.toString() + ":" + year;
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().inc("seq", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true).upsert(true);

        return mongoTemplate
                .findAndModify(query, update, options, Map.class, COUNTERS_COLLECTION)
                .map(doc -> {
                    Number seq = (Number) doc.get("seq");
                    return String.format("PRJ-%d-%03d", year, seq.intValue());
                })
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "Project code generation failed — counter returned empty", 3433, 500)));
    }
}
