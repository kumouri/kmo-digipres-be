package com.kumouri.kmodigipresbe.service.sync;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.sync.SyncChange;
import com.kumouri.kmodigipresbe.model.sync.SyncCursor;
import com.kumouri.kmodigipresbe.model.sync.SyncMutation;
import com.kumouri.kmodigipresbe.model.sync.SyncPushResult;
import com.kumouri.kmodigipresbe.model.sync.Tombstone;
import com.kumouri.kmodigipresbe.repository.SyncCursorRepository;
import com.kumouri.kmodigipresbe.repository.TombstoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mobile delta-sync service. Supports pull (changed docs + tombstones since a cursor)
 * and push (client mutations applied with last-write-wins per field). v1 allowlist:
 * {@code contacts}, {@code work_orders}, {@code activities}.
 *
 * <p>Conflict detection: a conflict is flagged when the server document was updated
 * after {@code mutation.clientUpdatedAt()} AND the server's current value differs
 * from the mutation's proposed value. Conflicted fields are still applied (LWW —
 * client wins); the caller receives the list in {@link SyncPushResult#conflictedFields()}.
 *
 * <p>Tombstones carry a 90-day TTL index so clients that have not synced within 90 days
 * must do a full reset (re-pull the entire collection).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    static final Map<String, String> ALLOWED_COLLECTIONS = Map.of(
            "contacts", "contacts",
            "work_orders", "work_orders",
            "activities", "activities"
    );

    /**
     * Security fix BE-07 — per-collection writable-field allowlist for the mobile
     * {@code push} path. {@code buildUpdate} previously {@code $set} every client-supplied
     * key verbatim; a malicious client could therefore {@code $set} {@code tenantId} to a
     * foreign UUID (moving the doc OUT of its tenant — data loss / cross-tenant write-out)
     * or overwrite any server-managed / internal field by id. The normal CRUD path is safe
     * (it patches scoped-loaded entities and {@code TenantStampingCallback} rejects a foreign
     * {@code tenantId} on save); the raw {@code updateFirst} sync path bypasses both, so the
     * allowlist is the dedicated guard.
     *
     * <p>Each key is the display-collection name (the {@code push} arg). Only fields a mobile
     * client legitimately edits offline are listed. {@code tenantId}, {@code _id}, {@code id},
     * {@code version}, {@code createdAt}, {@code updatedAt} are deliberately absent from every
     * set (server-managed — {@code updatedAt} is stamped by {@code buildUpdate} itself). A
     * field not in the set is dropped (logged) rather than failing the whole mutation, matching
     * the tolerant last-write-wins push contract (a mutation whose ONLY fields are all-dropped
     * applies nothing and returns {@code applied=false}).
     */
    static final Map<String, Set<String>> WRITABLE_FIELDS = Map.of(
            "contacts", Set.of(
                    "type", "firstName", "lastName", "displayName", "companyId",
                    "emails", "phones", "addresses", "tags", "subscriptionTopics",
                    "ownerId", "customFields"),
            "work_orders", Set.of(
                    "title", "jobSiteId", "status", "scheduledStart", "scheduledEnd",
                    "technicianUserId", "serviceType", "recurrenceRule", "parentWorkOrderId",
                    "notes", "completedAt", "completionSignatureRef", "completionPhotoRefs",
                    "customFields"),
            "activities", Set.of(
                    "type", "direction", "subjectType", "subjectId", "summary", "body",
                    "occurredAt", "dueAt", "completedAt", "ownerId", "payload", "customFields")
    );

    static final int MAX_MUTATION_BATCH = 500;

    private final ReactiveMongoTemplate mongo;
    private final SyncCursorRepository cursors;
    private final TombstoneRepository tombstones;

    /**
     * Pull all documents updated after {@code since} and all tombstones for deleted
     * IDs within the same window. Updates the per-(tenant, user, collection) cursor
     * to {@code Instant.now()} after the stream completes.
     *
     * @param collection display name (e.g., {@code "contacts"}) — must be in the allowlist
     */
    public Flux<SyncChange> pull(UUID tenantId, UUID userId, String collection, Instant since) {
        String mongoCollection = resolveCollection(collection);

        Query q = Query.query(
                Criteria.where("tenantId").is(tenantId)
                        .and("updatedAt").gt(since));

        Flux<SyncChange> updates = mongo.find(q, Document.class, mongoCollection)
                .map(doc -> toChange(doc, "UPDATE"));

        Flux<SyncChange> deletes = tombstones
                .findByTenantIdAndCollectionAndDeletedAtAfter(tenantId, collection, since)
                .map(t -> new SyncChange("DELETE", t.getDeletedId(), null, t.getDeletedAt()));

        Instant now = Instant.now();
        Mono<Void> advanceCursor = cursors
                .findByTenantIdAndUserIdAndCollection(tenantId, userId, collection)
                .defaultIfEmpty(SyncCursor.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .userId(userId)
                        .collection(collection)
                        .cursor(now)
                        .build())
                .map(c -> { c.setCursor(now); return c; })
                .flatMap(cursors::save)
                .then();

        return Flux.merge(updates, deletes)
                .concatWith(Flux.defer(() -> advanceCursor.thenMany(Flux.empty())));
    }

    /**
     * Apply a batch of client mutations. Each mutation is applied independently;
     * a failure on one does not abort the rest.
     *
     * @param collection display name — must be in the allowlist
     * @param mutations  client-side field deltas; must not exceed {@value MAX_MUTATION_BATCH}
     */
    public Flux<SyncPushResult> push(UUID tenantId, String collection, List<SyncMutation> mutations) {
        if (mutations.size() > MAX_MUTATION_BATCH) {
            return Flux.error(new DigiPresBeException(
                    "Mutation batch exceeds limit of " + MAX_MUTATION_BATCH, 1502, 400));
        }
        String mongoCollection = resolveCollection(collection);
        return Flux.fromIterable(mutations)
                .flatMap(m -> applyMutation(tenantId, collection, mongoCollection, m)
                        .onErrorResume(ex -> {
                            log.warn("SyncService: mutation {} in {} failed: {}",
                                    m.id(), collection, ex.toString());
                            return Mono.just(new SyncPushResult(m.id(), false, List.of()));
                        }));
    }

    /**
     * Record a deletion tombstone for {@code deletedId} in {@code collection}. Should
     * be called by services that delete allowlisted entities so offline clients can
     * discover the deletion on their next pull.
     */
    public Mono<Void> recordDeletion(UUID tenantId, String collection, UUID deletedId) {
        if (!ALLOWED_COLLECTIONS.containsKey(collection)) return Mono.empty();
        Tombstone t = Tombstone.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .collection(collection)
                .deletedId(deletedId)
                .deletedAt(Instant.now())
                .build();
        return tombstones.save(t).then();
    }

    // --- internals ---

    private Mono<SyncPushResult> applyMutation(
            UUID tenantId, String collection, String mongoCollection, SyncMutation mutation) {
        if (mutation.id() == null || mutation.fields() == null || mutation.fields().isEmpty()) {
            return Mono.just(new SyncPushResult(mutation.id(), false, List.of()));
        }
        // Security fix BE-07: filter the client-supplied fields down to the per-collection
        // writable allowlist BEFORE building the $set. A mutation that, after filtering, has
        // no writable fields left applies nothing (applied=false) without touching Mongo.
        Map<String, Object> writable = filterWritable(collection, mutation.fields(), mutation.id());
        if (writable.isEmpty()) {
            return Mono.just(new SyncPushResult(mutation.id(), false, List.of()));
        }
        Query findQ = Query.query(
                Criteria.where("_id").is(mutation.id()).and("tenantId").is(tenantId));

        return mongo.findOne(findQ, Document.class, mongoCollection)
                .flatMap(existing -> {
                    List<String> conflicted = detectConflicts(existing, mutation);
                    Update update = buildUpdate(writable);
                    return mongo.updateFirst(findQ, update, mongoCollection)
                            .map(r -> new SyncPushResult(mutation.id(), r.getModifiedCount() > 0, conflicted));
                })
                .defaultIfEmpty(new SyncPushResult(mutation.id(), false, List.of()))
                .doOnNext(result -> {
                    if (!result.conflictedFields().isEmpty()) {
                        log.info("SyncService: LWW conflict on {} {} fields={}",
                                mongoCollection, mutation.id(), result.conflictedFields());
                    }
                });
    }

    private static List<String> detectConflicts(Document existing, SyncMutation mutation) {
        if (mutation.clientUpdatedAt() == null) return List.of();
        Instant serverUpdatedAt = toInstant(existing.get("updatedAt"));
        if (serverUpdatedAt == null || !serverUpdatedAt.isAfter(mutation.clientUpdatedAt())) {
            return List.of();
        }
        List<String> conflicted = new ArrayList<>();
        for (Map.Entry<String, Object> entry : mutation.fields().entrySet()) {
            Object serverVal = existing.get(entry.getKey());
            if (serverVal != null && !serverVal.toString().equals(
                    entry.getValue() == null ? null : entry.getValue().toString())) {
                conflicted.add(entry.getKey());
            }
        }
        return conflicted;
    }

    /**
     * Security fix BE-07: keep only the keys in the collection's writable allowlist; drop
     * (and log, once per mutation) everything else. Server-managed and unknown fields —
     * {@code tenantId}, {@code _id}, {@code id}, {@code version}, {@code createdAt},
     * {@code updatedAt}, and any field absent from {@link #WRITABLE_FIELDS} — are never
     * applied. A collection with no allowlist entry (should not happen — every
     * {@code ALLOWED_COLLECTIONS} key has one) drops all fields, failing safe.
     */
    static Map<String, Object> filterWritable(String collection, Map<String, Object> fields, UUID id) {
        Set<String> allowed = WRITABLE_FIELDS.getOrDefault(collection, Set.of());
        Map<String, Object> writable = new HashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (allowed.contains(e.getKey())) {
                writable.put(e.getKey(), e.getValue());
            } else {
                dropped.add(e.getKey());
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("SyncService: dropped non-writable field(s) {} on {} push for id={}",
                    dropped, collection, id);
        }
        return writable;
    }

    private static Update buildUpdate(Map<String, Object> fields) {
        Update update = new Update();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            update.set(e.getKey(), e.getValue());
        }
        update.set("updatedAt", new Date());
        return update;
    }

    private static SyncChange toChange(Document doc, String type) {
        Map<String, Object> data = new HashMap<>(doc);
        Object rawId = data.remove("_id");
        String idStr = rawId == null ? null : rawId.toString();
        UUID id = null;
        try {
            if (idStr != null) id = UUID.fromString(idStr);
        } catch (IllegalArgumentException ignored) {
            // non-UUID _id — leave null
        }
        Instant changedAt = toInstant(doc.get("updatedAt"));
        data.put("id", idStr);
        return new SyncChange(type, id, data, changedAt);
    }

    private static Instant toInstant(Object val) {
        if (val instanceof Date d) return d.toInstant();
        if (val instanceof Instant i) return i;
        return null;
    }

    static String resolveCollection(String collection) {
        String mongoName = ALLOWED_COLLECTIONS.get(collection);
        if (mongoName == null) {
            throw new DigiPresBeException(
                    "Collection '" + collection + "' is not in the sync allowlist. "
                            + "Allowed: " + ALLOWED_COLLECTIONS.keySet(),
                    1500, 400);
        }
        return mongoName;
    }

    public static Set<String> allowedCollections() {
        return ALLOWED_COLLECTIONS.keySet();
    }
}
