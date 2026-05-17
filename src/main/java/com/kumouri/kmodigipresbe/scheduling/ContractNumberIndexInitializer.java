package com.kumouri.kmodigipresbe.scheduling;

import com.kumouri.kmodigipresbe.model.contract.Contract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Owns the {@code contracts.tenant_number_idx} index end-to-end as a
 * <strong>partial-unique</strong> index (Phase F — F-D10, the E.11 lesson applied
 * at design time).
 *
 * <h2>Why this exists</h2>
 * Spring Data's {@code @CompoundIndex} cannot express a
 * {@code partialFilterExpression} (the E.11 root cause — confirmed by the prior
 * implementation). {@link Contract} carries {@code @CompoundIndex(name="tenant_number_idx",
 * def="{'tenantId':1,'contractNumber':1}")} for the key layout only; the partial-unique
 * definition is owned entirely by this component, exactly as
 * {@link InvoiceNumberIndexInitializer} owns {@code invoices.tenant_number_idx}.
 *
 * <h2>What it creates</h2>
 * A unique compound index on {@code {tenantId:1, contractNumber:1}} with
 * {@code partialFilterExpression {contractNumber:{$type:"string"}}}. Uniqueness is
 * enforced ONLY for contracts that carry a (string) number. Many null-numbered
 * DRAFT contracts per tenant are allowed; issued contracts are still globally
 * unique per tenant.
 *
 * <h2>Idempotent + safe on every boot</h2>
 * Mirrors {@link InvoiceNumberIndexInitializer} exactly: runs on
 * {@link ApplicationReadyEvent}; reconciles absent / wrong-partial / correct in
 * the same three cases; {@code .block()}-ed on the startup thread; failure logged
 * and swallowed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractNumberIndexInitializer {

    static final String INDEX_NAME = "tenant_number_idx";

    /**
     * The MongoDB-supported "present and non-null" partial filter (mirrors
     * {@code InvoiceNumberIndexInitializer.PARTIAL_FILTER}).
     */
    static final Document PARTIAL_FILTER =
            new Document("contractNumber", new Document("$type", "string"));

    private static final Document INDEX_KEYS =
            new Document("tenantId", 1).append("contractNumber", 1);

    private final ReactiveMongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensurePartialUniqueIndex() {
        ReactiveIndexOperations indexOps = mongoTemplate.indexOps(Contract.class);

        Mono<Void> reconcile = indexOps.getIndexInfo()
                .filter(info -> INDEX_NAME.equals(info.getName()))
                .next()
                .flatMap(existing -> {
                    if (isDesiredPartialUnique(existing)) {
                        log.debug("contracts.tenant_number_idx already partial-unique — no-op.");
                        return Mono.empty();
                    }
                    log.info("Migrating contracts.tenant_number_idx → partial-unique "
                            + "(dropping existing: unique={}, partialFilter={}).",
                            existing.isUnique(), existing.getPartialFilterExpression());
                    return indexOps.dropIndex(INDEX_NAME)
                            .then(createPartialUnique(indexOps));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("contracts.tenant_number_idx absent — creating partial-unique.");
                    return createPartialUnique(indexOps).then(Mono.empty());
                }))
                .then();

        try {
            reconcile.block();
        } catch (RuntimeException e) {
            log.error("Failed to reconcile contracts.tenant_number_idx — "
                    + "contract numbering still works via the atomic counter; "
                    + "the unique index backstop may be missing until the next boot.", e);
        }
    }

    private boolean isDesiredPartialUnique(IndexInfo info) {
        if (!info.isUnique()) {
            return false;
        }
        String pfe = info.getPartialFilterExpression();
        if (pfe == null) {
            return false;
        }
        return PARTIAL_FILTER.equals(Document.parse(pfe));
    }

    private Mono<String> createPartialUnique(ReactiveIndexOperations indexOps) {
        CompoundIndexDefinition def = new CompoundIndexDefinition(INDEX_KEYS);
        def.named(INDEX_NAME)
                .unique()
                .partial(PartialIndexFilter.of(PARTIAL_FILTER));
        return indexOps.createIndex(def)
                .doOnSuccess(name -> log.info(
                        "Created partial-unique index {} {{tenantId:1,contractNumber:1}} "
                        + "partialFilterExpression {}", name, PARTIAL_FILTER.toJson()));
    }
}
