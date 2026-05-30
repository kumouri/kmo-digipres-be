package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Generates monotonically-increasing Work-Order numbers in the form
 * {@code YYYY-MM-{seq:04}} (e.g. {@code "2026-05-0001"}). The counter is
 * per-(tenant, calendar-month) so the first of each month automatically resets
 * the sequence.
 *
 * <p>A verbatim clone of {@link com.kumouri.kmodigipresbe.service.project.ProjectCodeGenerator}
 * with two differences: the counter is keyed per-<em>month</em>
 * ({@code _id = tenantId:YYYY-MM}) on a {@code work_order_number_counters}
 * collection, and the format is {@code YYYY-MM-NNNN} instead of {@code PRJ-YYYY-NNN}.
 *
 * <p>Implementation: an atomic MongoDB {@code findAndModify} with {@code $inc + upsert}.
 * This is the only correct mechanism for a monotonic counter without races or
 * holes ({@code count()+1} is race-prone and non-monotonic after deletes). The
 * counter document is intentionally NOT a
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped} entity — it is keyed by
 * a composite {@code _id} string and accessed via {@link ReactiveMongoTemplate}
 * (not a Spring-Data repository) to avoid the auto-tenant-filter. Year/month are
 * always UTC (consistent with {@code Instant.now()} elsewhere).
 *
 * <p>Per the owner decision, work-order numbers rely on the atomic counter alone
 * — no unique index/backstop (they are not money-critical like invoice numbers).
 */
@RequiredArgsConstructor
public class WorkOrderNumberGenerator {

    private static final String COUNTERS_COLLECTION = "work_order_number_counters";

    private final ReactiveMongoTemplate mongoTemplate;

    /**
     * Returns the next {@code YYYY-MM-{seq:04}} number for the given tenant.
     * Atomic: two concurrent calls for the same (tenant, month) always get
     * distinct seq values.
     *
     * @param tenantId the owning tenant
     * @return e.g. {@code "2026-05-0001"}
     */
    public Mono<String> next(UUID tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int year = today.getYear();
        int month = today.getMonthValue();
        String period = String.format("%04d-%02d", year, month);
        String id = tenantId.toString() + ":" + period;
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().inc("seq", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true).upsert(true);

        return mongoTemplate
                .findAndModify(query, update, options, Map.class, COUNTERS_COLLECTION)
                .map(doc -> {
                    Number seq = (Number) doc.get("seq");
                    return String.format("%s-%04d", period, seq.intValue());
                })
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "Work-order number generation failed — counter returned empty", 1332, 500)));
    }
}
