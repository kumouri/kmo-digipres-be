package com.kumouri.kmodigipresbe.service.billing;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
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
 * Generates monotonically-increasing Invoice numbers in the form
 * {@code INV-{YYYY}-{NNNN}} (Phase E — the user-authorized resolution of the
 * escalated {@code tenant_number_idx} blocker). The counter is per-(tenant, year)
 * so January 1st automatically resets the sequence for the new year. Numbers are
 * assigned at the DRAFT→issued edge (finalize-time) by {@link InvoiceService}, not
 * at create-time — DRAFT invoices stay {@code invoiceNumber == null} (which is why
 * {@code Invoice.tenant_number_idx} is migrated to a <em>partial</em> unique index
 * by {@code InvoiceNumberIndexInitializer}; many null-numbered DRAFTs per tenant
 * are now allowed and only issued invoices carry a number).
 *
 * <p>Implementation: an atomic MongoDB {@code findAndModify} with {@code $inc + upsert}
 * on an {@code invoice_number_counters} collection keyed by {@code _id = tenantId:year}.
 * This is the only correct mechanism for a monotonic counter without races or holes:
 * {@code count()+1} is race-prone and non-monotonic after deletes; a version-loop scan
 * requires more round-trips without a stronger guarantee. The
 * {@link com.kumouri.kmodigipresbe.model.billing.Invoice} {@code tenant_number_idx}
 * partial-unique compound index is the correctness backstop — if a race ever slips
 * through (it shouldn't), the second save throws {@code DuplicateKeyException} and
 * {@link InvoiceService} retries once with a fresh increment. This mirrors
 * {@link com.kumouri.kmodigipresbe.service.project.ProjectCodeGenerator} exactly.
 *
 * <p>The counter document is intentionally NOT a
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped} entity — it is keyed by a
 * composite {@code _id} string, like the Mongo-native counter pattern. It is accessed
 * via {@link ReactiveMongoTemplate} (not a Spring-Data repository) to avoid the
 * auto-tenant-filter that {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository}
 * applies (cross-cutting infra, exactly like the project-code counter). Year is
 * always UTC (consistent with {@code Instant.now()} elsewhere in the codebase).
 */
@Component
@RequiredArgsConstructor
public class InvoiceNumberGenerator {

    private static final String COUNTERS_COLLECTION = "invoice_number_counters";

    private final ReactiveMongoTemplate mongoTemplate;

    /**
     * Returns the next {@code INV-{YYYY}-{NNNN}} number for the given tenant.
     * Atomic: two concurrent calls for the same (tenant, year) always get distinct
     * seq values. 4-digit zero-padded; per-(tenant, year); the January reset is
     * automatic via the year-keyed {@code _id}.
     *
     * @param tenantId the owning tenant
     * @return e.g. {@code "INV-2026-0001"}
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
                    return String.format("INV-%d-%04d", year, seq.intValue());
                })
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "Invoice number generation failed — counter returned empty", 3640, 500)));
    }
}
