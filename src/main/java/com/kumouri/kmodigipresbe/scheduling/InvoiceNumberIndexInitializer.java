package com.kumouri.kmodigipresbe.scheduling;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
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
 * Owns the {@code invoices.tenant_number_idx} index end-to-end as a
 * <strong>partial unique</strong> index — the user-authorized resolution of the
 * escalated {@code tenant_number_idx} blocker (Phase E; an explicitly
 * user-authorized override of plan §7's numbering non-goal, recorded as an
 * authorized scope change).
 *
 * <h2>Why this exists</h2>
 * The pre-existing index was declared on {@link Invoice} as
 * {@code @CompoundIndex(name="tenant_number_idx", def="{tenantId:1,invoiceNumber:1}",
 * unique=true)} — <em>non-sparse, non-partial</em>. MongoDB indexes a missing/null
 * key, so a tenant could hold at most ONE {@code invoiceNumber==null} invoice ever;
 * the 2nd null-numbered invoice for a tenant failed with {@code E11000}. That broke
 * the Phase-E recurring multi-period catch-up and was a latent defect for the
 * Phase-C milestone-spawn / Phase-D time-and-expense-spawn paths too (all create
 * unnumbered DRAFT invoices). Spring Data's {@code @CompoundIndex} cannot express a
 * {@code partialFilterExpression}, so the annotation was removed from {@link Invoice}
 * and the index is created here instead.
 *
 * <h2>What it creates</h2>
 * A unique compound index on {@code {tenantId:1, invoiceNumber:1}} with
 * {@code partialFilterExpression {invoiceNumber:{$type:"string"}}}. Uniqueness is
 * enforced ONLY for invoices that carry a (string) number — i.e. issued invoices
 * numbered at the DRAFT→issued edge by {@code InvoiceNumberGenerator}. Many
 * null-numbered DRAFTs per tenant are now allowed; numbered invoices are still
 * globally unique per tenant. ({@code $type:"string"} is the MongoDB-supported
 * spelling of "present and non-null"; {@code $ne:null} is NOT a legal
 * partialFilterExpression operator, and a bare {@code $exists:true} would still
 * match the BSON-null DRAFT documents — the very rows that must be excluded.)
 *
 * <h2>Idempotent + safe on every boot</h2>
 * Runs on {@link ApplicationReadyEvent} (the {@code QuartzBootstrap} pattern — the
 * scheduler/Mongo are fully up). Spring's {@code auto-index-creation} will NOT
 * alter an existing index, so this component reconciles explicitly:
 * <ul>
 *   <li><strong>Index absent</strong> (fresh Testcontainers DB / new prod DB):
 *       create the partial-unique index.</li>
 *   <li><strong>Index present but NOT the desired partial definition</strong>
 *       (a prod DB carrying the old non-partial unique index, or any drift):
 *       drop it, then create the partial-unique index.</li>
 *   <li><strong>Index present and already the desired partial definition</strong>
 *       (this app already migrated it; subsequent cached {@code @SpringBootTest}
 *       contexts share one Mongo): no-op.</li>
 * </ul>
 * The reactive chain is {@code .block()}-ed inside the listener: this runs on the
 * startup thread (NOT the Netty event loop) and must complete before traffic is
 * served so the first numbered invoice is protected by the correct index. A
 * failure is logged and swallowed (mirrors {@code QuartzBootstrap}) — the atomic
 * {@code InvoiceNumberGenerator} counter is the primary correctness mechanism;
 * the index is the defense-in-depth backstop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceNumberIndexInitializer {

    static final String INDEX_NAME = "tenant_number_idx";

    /**
     * The MongoDB-supported "present and non-null" partial filter. Kept as a
     * constant so the boot-time reconcile and any test assert the SAME expression.
     */
    static final Document PARTIAL_FILTER =
            new Document("invoiceNumber", new Document("$type", "string"));

    private static final Document INDEX_KEYS =
            new Document("tenantId", 1).append("invoiceNumber", 1);

    private final ReactiveMongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensurePartialUniqueIndex() {
        ReactiveIndexOperations indexOps = mongoTemplate.indexOps(Invoice.class);

        Mono<Void> reconcile = indexOps.getIndexInfo()
                .filter(info -> INDEX_NAME.equals(info.getName()))
                .next()
                .flatMap(existing -> {
                    if (isDesiredPartialUnique(existing)) {
                        log.debug("tenant_number_idx already partial-unique — no migration needed.");
                        return Mono.empty();
                    }
                    log.info("Migrating tenant_number_idx → partial-unique "
                            + "(dropping existing definition: unique={}, partialFilter={}).",
                            existing.isUnique(), existing.getPartialFilterExpression());
                    return indexOps.dropIndex(INDEX_NAME)
                            .then(createPartialUnique(indexOps));
                })
                // No tenant_number_idx at all (fresh DB) — just create it.
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("tenant_number_idx absent — creating partial-unique index.");
                    return createPartialUnique(indexOps).then(Mono.empty());
                }))
                .then();

        try {
            reconcile.block();
        } catch (RuntimeException e) {
            // Mirror QuartzBootstrap: log and continue. The atomic
            // InvoiceNumberGenerator counter is the primary guarantee; this index
            // is the backstop. A startup that can't reconcile the index should not
            // hard-fail the whole app (and on the shared singleton Testcontainers
            // Mongo a benign race between cached contexts must not break the suite).
            log.error("Failed to reconcile the partial-unique tenant_number_idx — "
                    + "invoice numbering still works via the atomic counter; the unique "
                    + "index backstop may be missing until the next clean boot.", e);
        }
    }

    /**
     * True iff the existing index is unique AND its partialFilterExpression equals
     * our desired {@code {invoiceNumber:{$type:"string"}}}. A non-partial unique
     * index (the old definition), a non-unique index, or a differently-filtered
     * partial index all return false → drop + recreate.
     */
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
                        "Created partial-unique index {} {{tenantId:1,invoiceNumber:1}} "
                        + "partialFilterExpression {}", name, PARTIAL_FILTER.toJson()));
    }
}
