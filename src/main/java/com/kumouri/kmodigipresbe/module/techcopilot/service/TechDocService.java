package com.kumouri.kmodigipresbe.module.techcopilot.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.techcopilot.ingest.TechDocChunker;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDoc;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDocRepository;
import com.kumouri.kmodigipresbe.repository.VectorDocumentRepository;
import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tech Copilot (T13) — owns the {@link TechDoc} CRUD <strong>and the chunk-and-embed ingest</strong> (the
 * T13 §3 crux). The {@link ListingDisclosureService}-equivalent for the field-tech corpus.
 *
 * <p><strong>Why the module owns the embed+upsert (the {@code ListingDisclosureService} rationale):</strong>
 * the core {@code EmbeddingPipeline.indexAttachment} embeds only a file's filename+content-type, so a
 * manual indexed that way would never ground a real question. Instead, on create/update this service
 * <em>chunks</em> the doc text ({@link TechDocChunker}) and embeds <strong>each chunk</strong> directly as
 * embedding source type {@link RagRetrievalService#TECH_DOC_SOURCE_TYPE "TechDoc"} carrying
 * {@code techDocId}/{@code chunkIndex} metadata — the same direct-upsert shape
 * {@code EmbeddingPipeline.embedAndUpsert} uses, just owned by the module, per chunk, and carrying the doc
 * id (so {@code RagRetrievalService.retrieveForCorpus} can scope to the tenant's manual corpus and a cited
 * answer can attribute the source). The core pipeline is untouched (blast-radius zero).
 *
 * <p><strong>Deterministic chunk vector id (T13-D1):</strong> each chunk's vector {@code sourceId} is
 * {@code UUID.nameUUIDFromBytes(techDocId + ":" + chunkIndex)} so a re-index <em>upserts</em> each chunk in
 * place (no duplicates). When a re-edited doc has <em>fewer</em> chunks than before, the stale trailing
 * chunk vectors ({@code [newCount, oldCount)}) are explicitly deleted — keeping the index consistent across
 * edits without ever leaking another doc's vectors.
 *
 * <p><strong>Best-effort indexing (T13-D2):</strong> the doc save always succeeds first; an
 * embedding/upsert failure logs {@code 4492} and leaves {@code indexedAt} null (a re-index can retry) — the
 * doc is never lost. On success {@code indexedAt} + {@code chunkCount} are stamped and {@code TECH_DOC_INDEXED}
 * is emitted.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code TechCopilotAutoConfiguration} (no {@code @Service}
 * annotation) so it exists only when the module is enabled.
 */
@Slf4j
public class TechDocService {

    /** Per-chunk text is truncated to this before embedding (the {@code EmbeddingPipeline} cap). */
    static final int MAX_SNIPPET_CHARS = 8_000;
    /** The {@code contentPreview} metadata cap (matches {@code EmbeddingPipeline.MAX_PREVIEW_CHARS}). */
    static final int MAX_PREVIEW_CHARS = 500;

    private final TechDocRepository docs;
    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final VectorDocumentRepository vectors;
    private final DomainEventPublisher events;
    private final int chunkSize;
    private final int chunkOverlap;

    public TechDocService(TechDocRepository docs,
                          EmbeddingService embeddingService,
                          VectorIndex vectorIndex,
                          VectorDocumentRepository vectors,
                          DomainEventPublisher events,
                          int chunkSize,
                          int chunkOverlap) {
        this.docs = docs;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.vectors = vectors;
        this.events = events;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * Creates a doc and chunk-and-embeds its text. Save-then-index: the returned doc reflects the
     * post-index state ({@code indexedAt}/{@code chunkCount} set on success, {@code indexedAt} null if
     * indexing degraded). {@code 4491} if title or text is blank.
     */
    public Mono<TechDoc> create(TechDoc doc) {
        if (doc == null || doc.getTitle() == null || doc.getTitle().isBlank()
                || doc.getText() == null || doc.getText().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Tech doc title and text are required", 4491, 400));
        }
        return TenantContextHolder.required().flatMap(ctx -> {
            TechDoc toSave = doc.toBuilder()
                    .id(UUID.randomUUID())
                    .tenantId(ctx.tenantId())
                    .chunkCount(0)
                    .indexedAt(null)
                    .build();
            return docs.save(toSave)
                    .flatMap(saved -> indexDoc(ctx.tenantId(), saved, 0));
        });
    }

    /**
     * Updates a doc's title/type/source/text and re-indexes. {@code 4490} if not found for the tenant. A
     * shrunk doc's stale trailing chunk vectors are deleted (T13-D1).
     */
    public Mono<TechDoc> update(UUID id, TechDoc patch) {
        return TenantContextHolder.required().flatMap(ctx ->
                docs.findByIdAndTenantId(id, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Tech doc not found", 4490, 404)))
                        .flatMap(existing -> {
                            int priorChunks = existing.getChunkCount();
                            TechDoc updated = existing.toBuilder()
                                    .title(patch.getTitle() != null ? patch.getTitle() : existing.getTitle())
                                    .equipmentType(patch.getEquipmentType() != null
                                            ? patch.getEquipmentType() : existing.getEquipmentType())
                                    .source(patch.getSource() != null ? patch.getSource() : existing.getSource())
                                    .text(patch.getText() != null ? patch.getText() : existing.getText())
                                    .indexedAt(null)
                                    .build();
                            return docs.save(updated)
                                    .flatMap(saved -> indexDoc(ctx.tenantId(), saved, priorChunks));
                        }));
    }

    public Flux<TechDoc> list() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> docs.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId()));
    }

    public Mono<TechDoc> get(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> docs.findByIdAndTenantId(id, ctx.tenantId()))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Tech doc not found", 4490, 404)));
    }

    /**
     * Chunks the doc text, embeds + upserts each chunk as source type {@code "TechDoc"} with
     * {@code techDocId}/{@code chunkIndex} metadata, deletes any stale trailing chunks from a prior larger
     * index, then stamps {@code indexedAt}/{@code chunkCount}, re-saves, and emits {@code TECH_DOC_INDEXED}.
     * Best-effort: a blank text or any embedding/upsert failure logs {@code 4492} and returns the
     * saved-but-unindexed doc (never throws, never loses the row).
     */
    private Mono<TechDoc> indexDoc(UUID tenantId, TechDoc saved, int priorChunkCount) {
        List<String> chunks = TechDocChunker.chunk(saved.getText(), chunkSize, chunkOverlap);
        if (chunks.isEmpty()) {
            log.warn("T13: tech doc {} produced no chunks (blank text) — skipping indexing (4492)",
                    saved.getId());
            return Mono.just(saved);
        }
        String typeName = saved.getEquipmentType() != null
                ? saved.getEquipmentType().name() : "GENERAL";

        // Upsert each chunk in order (sequential — keeps the shared mock EmbeddingService deterministic and
        // the per-tenant AiUsageRecorder write serialized, the ListingPrep "sequential, not Mono.zip" note).
        Flux<Void> upserts = Flux.range(0, chunks.size())
                .concatMap(i -> {
                    String chunk = chunks.get(i);
                    UUID chunkVectorId = chunkVectorId(saved.getId(), i);
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("techDocId", saved.getId().toString());
                    meta.put("techDocTitle", saved.getTitle() != null ? saved.getTitle() : "");
                    meta.put("equipmentType", typeName);
                    meta.put("chunkIndex", i);
                    meta.put("contentPreview", truncate(chunk, MAX_PREVIEW_CHARS));
                    meta.put("title", saved.getTitle() != null ? saved.getTitle() : typeName);
                    return embeddingService.embed(tenantId, truncate(chunk, MAX_SNIPPET_CHARS))
                            .flatMap(vector -> vectorIndex.upsert(tenantId,
                                    RagRetrievalService.TECH_DOC_SOURCE_TYPE, chunkVectorId, vector, meta));
                });

        return upserts.then(deleteStaleChunks(tenantId, saved.getId(), chunks.size(), priorChunkCount))
                .then(docs.save(saved.toBuilder()
                        .indexedAt(Instant.now())
                        .chunkCount(chunks.size())
                        .build()))
                .doOnNext(indexed -> events.publish(DomainEvent.of(
                        DomainEventType.TECH_DOC_INDEXED, tenantId, indexed.getId(),
                        Map.of("techDocId", indexed.getId(),
                                "equipmentType", typeName,
                                "chunkCount", indexed.getChunkCount()))))
                .onErrorResume(err -> {
                    log.warn("T13: tech-doc chunk indexing failed for {} (best-effort, indexedAt left null, "
                            + "4492): {}", saved.getId(), err.toString());
                    return Mono.just(saved);
                });
    }

    /**
     * Removes the stale trailing chunk vectors {@code [newChunkCount, priorChunkCount)} left behind when a
     * re-edited doc shrank. Uses only the existing repo finder + the inherited {@code delete} (no shared
     * vector-index/repo change). A no-op when the doc grew or stayed the same size.
     */
    private Mono<Void> deleteStaleChunks(UUID tenantId, UUID techDocId, int newChunkCount, int priorChunkCount) {
        if (priorChunkCount <= newChunkCount) {
            return Mono.empty();
        }
        List<Integer> staleIndices = new ArrayList<>();
        for (int i = newChunkCount; i < priorChunkCount; i++) {
            staleIndices.add(i);
        }
        return Flux.fromIterable(staleIndices)
                .concatMap(i -> vectors.findByTenantIdAndSourceTypeAndSourceId(
                                tenantId, RagRetrievalService.TECH_DOC_SOURCE_TYPE,
                                chunkVectorId(techDocId, i))
                        .flatMap(vectors::delete))
                .then();
    }

    /** Deterministic per-chunk vector id so a re-index upserts each chunk in place (T13-D1). */
    private static UUID chunkVectorId(UUID techDocId, int chunkIndex) {
        return UUID.nameUUIDFromBytes(
                (techDocId.toString() + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
