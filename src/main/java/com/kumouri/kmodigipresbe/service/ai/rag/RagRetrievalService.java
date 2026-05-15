package com.kumouri.kmodigipresbe.service.ai.rag;

import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Retrieves the most relevant context chunks for a question via vector
 * similarity search, scoped to a single tenant.
 *
 * <p>Optional scope filters ({@code scopeContactId}, {@code scopeDealId}) narrow
 * results to entities linked to a specific contact or deal. The filter is applied
 * in-memory after the vector search because Atlas pre-filters only support simple
 * equality on indexed fields; adding sourceId as a pre-filter index would require
 * per-entity vector index variants, which is overkill at KMOSF scale.
 *
 * <p>Falls back to an empty flux if the vector index is unavailable (e.g. the
 * Atlas Vector Search index has not been created yet on a fresh cluster). The
 * caller still gets an answer — just without cited context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    static final int DEFAULT_TOP_K = 8;

    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;

    public record RetrievedChunk(String sourceType, UUID sourceId,
                                  String contentPreview, double score) {
    }

    /**
     * Retrieves top-K chunks most similar to {@code question} for the given tenant.
     *
     * @param tenantId       the tenant scope — mandatory
     * @param question       the natural-language question to embed as a query vector
     * @param scopeContactId optional: restrict results to activities/messages linked
     *                       to this contact. {@code null} = no restriction.
     * @param scopeDealId    optional: restrict results to quotes linked to this deal.
     *                       {@code null} = no restriction.
     * @return hits ordered by descending similarity score
     */
    public Flux<RetrievedChunk> retrieve(UUID tenantId, String question,
                                         @Nullable UUID scopeContactId,
                                         @Nullable UUID scopeDealId) {
        return embeddingService.embed(tenantId, question)
                .flatMapMany(vector -> vectorIndex.search(tenantId, vector, DEFAULT_TOP_K, null))
                .filter(hit -> hit.sourceId() != null)
                .filter(hit -> scopeContactId == null || matchesContact(hit, scopeContactId))
                .filter(hit -> scopeDealId == null || matchesDeal(hit, scopeDealId))
                .map(hit -> new RetrievedChunk(
                        hit.sourceType(),
                        hit.sourceId(),
                        previewFrom(hit),
                        hit.score()))
                .onErrorResume(err -> {
                    log.warn("RAG retrieval failed for tenant {}: {}", tenantId, err.toString());
                    return Flux.empty();
                });
    }

    private static boolean matchesContact(VectorIndex.VectorSearchHit hit, UUID contactId) {
        Object val = hit.metadata() != null ? hit.metadata().get("contactId") : null;
        return contactId.toString().equals(val instanceof String ? val : (val != null ? val.toString() : null));
    }

    private static boolean matchesDeal(VectorIndex.VectorSearchHit hit, UUID dealId) {
        Object val = hit.metadata() != null ? hit.metadata().get("dealId") : null;
        return dealId.toString().equals(val instanceof String ? val : (val != null ? val.toString() : null));
    }

    private static String previewFrom(VectorIndex.VectorSearchHit hit) {
        if (hit.metadata() == null) return "";
        Object preview = hit.metadata().get("contentPreview");
        return preview != null ? preview.toString() : "";
    }
}
