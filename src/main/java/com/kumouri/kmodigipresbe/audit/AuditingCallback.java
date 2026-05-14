package com.kumouri.kmodigipresbe.audit;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.mapping.event.ReactiveAfterSaveCallback;
import org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeSaveCallback;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Records CREATE / UPDATE events for every {@link Auditable} entity. DELETE is not
 * captured here (Spring Data MongoDB has no reactive delete callback); services
 * call {@link AuditEventWriter#auditDelete} directly.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>{@code BeforeSave}: load the prior document by {@code (_id, tenantId)} via
 *       {@link ReactiveMongoOperations} (defense-in-depth tenant filter — the entity
 *       has been through {@code TenantStampingCallback} so its {@code tenantId} is
 *       trusted at this point). If a prior exists, compute diffs via
 *       {@link AuditDiffComputer}; otherwise this is a CREATE. Stash the planned
 *       op + diffs in the {@link #pending} map keyed by entity id.</li>
 *   <li>{@code AfterSave}: drain the pending plan and write the audit event via
 *       {@link AuditEventWriter}. If the actual Mongo save failed, AfterSave never
 *       fires and the audit event is never written — what we want.</li>
 * </ol>
 *
 * <p>Ordered {@link Ordered#LOWEST_PRECEDENCE} so it runs <em>after</em> any other
 * {@code BeforeSaveCallback} that may reject the save (e.g. {@code CustomFieldValidator}).
 * If a higher-priority callback throws, this one never runs and no audit plan is
 * recorded.
 *
 * <p>{@link #pending} leaks a tiny amount of memory on the rare path where BeforeSave
 * completes but the Mongo write fails after our callback returns (network blip, etc.).
 * Each leaked entry is one small record; on app restart the map clears. Worth fixing
 * with a Caffeine TTL only if a production incident shows it matters.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditingCallback implements
        ReactiveBeforeSaveCallback<Auditable>,
        ReactiveAfterSaveCallback<Auditable> {

    private final ObjectProvider<ReactiveMongoOperations> operationsProvider;
    private final AuditEventWriter writer;

    private final ConcurrentMap<UUID, AuditPlan> pending = new ConcurrentHashMap<>();

    public AuditingCallback(ObjectProvider<ReactiveMongoOperations> operationsProvider,
                            AuditEventWriter writer) {
        this.operationsProvider = operationsProvider;
        this.writer = writer;
    }

    @Override
    @NonNull
    public Publisher<Auditable> onBeforeSave(@NonNull Auditable entity,
                                             @NonNull Document target,
                                             @NonNull String collection) {
        UUID id = entity.getId();
        if (id == null) {
            // UuidIdAutogenCallback runs in BeforeConvert and assigns an id, so by the
            // time BeforeSave fires the id is non-null in every codepath. Defensive
            // fallback: skip auditing for an entity that somehow arrives without an id.
            return Mono.just(entity);
        }
        return loadPrior(entity)
                .map(prior -> new AuditPlan(AuditOp.UPDATE, AuditDiffComputer.diff(prior, entity)))
                .defaultIfEmpty(new AuditPlan(AuditOp.CREATE, List.of()))
                .doOnNext(plan -> pending.put(id, plan))
                .thenReturn(entity)
                .onErrorResume(err -> {
                    // An audit-log infrastructure failure (prior-load Mongo blip, etc.)
                    // must never block the business save. AfterSave will skip too because
                    // pending will not contain an entry for this id.
                    log.warn("Audit pre-save load failed for {}/{}; save proceeds without audit",
                            entity.getAuditEntityType(), id, err);
                    return Mono.just(entity);
                });
    }

    @Override
    @NonNull
    public Publisher<Auditable> onAfterSave(@NonNull Auditable entity,
                                            @NonNull Document target,
                                            @NonNull String collection) {
        UUID id = entity.getId();
        if (id == null) return Mono.just(entity);
        AuditPlan plan = pending.remove(id);
        if (plan == null) return Mono.just(entity);
        // Skip CREATEs with an empty diff list to avoid noise? No — CREATE matters even
        // without diffs because the "what was created" is the entity itself, reconstructible
        // from the timestamp and entity id.
        return writer.writeChange(entity, plan.op(), plan.diffs())
                .thenReturn(entity);
    }

    private Mono<? extends Auditable> loadPrior(Auditable entity) {
        Query q = new Query(Criteria.where("_id").is(entity.getId())
                .and("tenantId").is(entity.getTenantId()));
        @SuppressWarnings("unchecked")
        Class<? extends Auditable> clazz = (Class<? extends Auditable>) entity.getClass();
        return operationsProvider.getObject().findOne(q, clazz);
    }

    private record AuditPlan(AuditOp op, List<FieldDiff> diffs) {
    }
}
