package com.kumouri.kmodigipresbe.service.ai.vector;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant vector index. Stores and retrieves dense embeddings keyed by
 * source entity, with mandatory tenant isolation at every call site.
 *
 * <p><strong>Tenancy contract:</strong> every method takes {@code tenantId} as
 * its first parameter and must apply it as a hard filter. There is no overload
 * that omits it. This is enforced by {@code ArchUnitVectorTenancyTest}.
 */
public interface VectorIndex {

    /**
     * Inserts or replaces the embedding for a source entity.
     *
     * @param tenantId   the owning tenant — never {@code null}
     * @param sourceType stable entity type label, e.g. {@code "Activity"}
     * @param sourceId   the entity's id
     * @param vector     the embedding vector (1536 dimensions for text-embedding-3-small)
     * @param metadata   arbitrary display data stored alongside the vector
     *                   (e.g. {@code contentPreview}, {@code title})
     */
    Mono<Void> upsert(UUID tenantId, String sourceType, UUID sourceId,
                      float[] vector, Map<String, Object> metadata);

    /**
     * Nearest-neighbour search within a single tenant's vectors.
     *
     * @param tenantId      the tenant scope — never {@code null}
     * @param queryVector   the query embedding to find neighbours for
     * @param topK          maximum number of results to return
     * @param keywordFilter optional case-insensitive regex applied to
     *                      {@code metadata.contentPreview}; {@code null} = no filter
     * @return hits ordered by descending similarity score
     */
    Flux<VectorSearchHit> search(UUID tenantId, float[] queryVector, int topK,
                                 @Nullable String keywordFilter);

    record VectorSearchHit(String sourceType, UUID sourceId, double score,
                           Map<String, Object> metadata) {}
}
