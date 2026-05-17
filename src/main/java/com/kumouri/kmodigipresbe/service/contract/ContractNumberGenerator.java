package com.kumouri.kmodigipresbe.service.contract;

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
 * Generates monotonically-increasing Contract numbers in the form
 * {@code CTR-{YYYY}-{NNNN}} (Phase F — F-D10). A verbatim clone of
 * {@link com.kumouri.kmodigipresbe.service.billing.InvoiceNumberGenerator}
 * (replace {@code INV} → {@code CTR}, collection {@code contract_number_counters},
 * defensive errorCode {@code 3708}).
 *
 * <p>The counter is per-(tenant, year) so January 1st automatically resets the
 * sequence for the new year. Numbers are assigned at the first DRAFT→SENT edge by
 * {@link ContractService}, not at create-time — DRAFT contracts stay
 * {@code contractNumber == null} (why {@code Contract.tenant_number_idx} is a
 * partial-unique index owned by {@code scheduling.ContractNumberIndexInitializer}).
 *
 * <p>Implementation: an atomic MongoDB {@code findAndModify} with
 * {@code $inc + upsert} on a {@code contract_number_counters} collection keyed by
 * {@code _id = tenantId:year}. This is the only correct mechanism for a monotonic
 * counter without races or holes. The partial-unique {@code tenant_number_idx} on
 * {@code Contract} is the correctness backstop — if a race ever slips through (it
 * shouldn't), the second save throws {@code DuplicateKeyException} and
 * {@link ContractService} retries once with a fresh increment
 * (the {@code ProjectService.generateCodeAndSave} C-D3 pattern).
 *
 * <p>The counter document is intentionally NOT a
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped} entity — it is keyed by a
 * composite {@code _id} string, like the Mongo-native counter pattern. It is accessed
 * via {@link ReactiveMongoTemplate} (not a Spring-Data repository) to avoid the
 * auto-tenant-filter. Year is always UTC.
 */
@Component
@RequiredArgsConstructor
public class ContractNumberGenerator {

    private static final String COUNTERS_COLLECTION = "contract_number_counters";

    private final ReactiveMongoTemplate mongoTemplate;

    /**
     * Returns the next {@code CTR-{YYYY}-{NNNN}} number for the given tenant.
     * Atomic: two concurrent calls for the same (tenant, year) always get distinct
     * seq values. 4-digit zero-padded; per-(tenant, year); the January reset is
     * automatic via the year-keyed {@code _id}.
     *
     * @param tenantId the owning tenant
     * @return e.g. {@code "CTR-2026-0001"}
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
                    return String.format("CTR-%d-%04d", year, seq.intValue());
                })
                .switchIfEmpty(Mono.error(new DigiPresBeException(
                        "Contract number generation failed — counter returned empty", 3708, 500)));
    }
}
