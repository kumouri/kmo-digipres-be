package com.kumouri.kmodigipresbe.service.ai.rag;

import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.Nullable;
import java.util.Map;
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

    /**
     * Listing-scoped retrieval for the Real Estate Concierge (RE-1). Additive overload — the
     * contact/deal {@link #retrieve} signature is byte-unchanged so NMM / all existing callers are
     * unaffected.
     *
     * <p>Two guards make the grounding "scoped" claim hold (RE-1 §6.4):
     * <ol>
     *   <li>{@code sourceType == "ListingDisclosure"} — only disclosure vectors, never generic CRM
     *       activity/quote/email vectors, can ground a listing answer;</li>
     *   <li>{@code matchesListing} on {@code metadata.listingId} — only <em>this</em> listing's
     *       disclosures, so listing A's question can never retrieve listing B's chunk.</li>
     * </ol>
     * {@code topK} is intentionally larger than {@link #DEFAULT_TOP_K} (the in-memory filter narrows the
     * Atlas top-K down to one listing's hits). Falls back to an empty flux if the vector index is
     * unavailable — the concierge then short-circuits to {@code HANDOFF} (never grounds on nothing).
     *
     * @param tenantId  the tenant scope — mandatory
     * @param question  the buyer's question to embed as a query vector
     * @param listingId the listing whose disclosures are the only eligible grounding corpus
     * @param topK      candidates to pull from Atlas before the listing/source-type filter
     */
    public Flux<RetrievedChunk> retrieveForListing(UUID tenantId, String question,
                                                   UUID listingId, int topK) {
        return embeddingService.embed(tenantId, question)
                .flatMapMany(vector -> vectorIndex.search(tenantId, vector, topK, null))
                .filter(hit -> hit.sourceId() != null)
                .filter(hit -> LISTING_DISCLOSURE_SOURCE_TYPE.equals(hit.sourceType()))
                .filter(hit -> matchesListing(hit, listingId))
                .map(hit -> new RetrievedChunk(
                        hit.sourceType(),
                        hit.sourceId(),
                        previewFrom(hit),
                        hit.score()))
                .onErrorResume(err -> {
                    log.warn("Listing-scoped RAG retrieval failed for tenant {} listing {}: {}",
                            tenantId, listingId, err.toString());
                    return Flux.empty();
                });
    }

    /** The embedding source type for {@code ListingDisclosure} text (RE-1 §3). */
    public static final String LISTING_DISCLOSURE_SOURCE_TYPE = "ListingDisclosure";

    /** The embedding source type for Tech Copilot manual/SOP/spec-sheet chunks (T13 §3). */
    public static final String TECH_DOC_SOURCE_TYPE = "TechDoc";

    /**
     * A corpus-scoped hit (T13) that additionally carries the chunk's full {@code metadata} map, so the
     * caller can build <em>doc-level</em> citations (e.g. group N chunks of the same manual under one
     * {@code techDocId}/{@code techDocTitle}). Additive — distinct from {@link RetrievedChunk} (which the RE
     * concierge path uses), so that record stays byte-unchanged.
     */
    public record CorpusChunk(UUID chunkId, String contentPreview, double score,
                              Map<String, Object> metadata) {
    }

    /**
     * Corpus-scoped retrieval for the Tech Copilot (T13). Additive overload — a sibling of
     * {@link #retrieveForListing} with only the source-type guard (no per-entity filter): a technician's
     * question searches the <em>whole</em> tenant corpus of one embedding source type, and the document
     * identity rides in each hit's metadata (so the cited answer can attribute it). The contact/deal
     * {@link #retrieve} and the listing {@link #retrieveForListing} signatures are byte-unchanged, so every
     * existing caller (NMM / the RE concierge) is unaffected.
     *
     * <p>The {@code sourceType} guard ({@code "TechDoc"}) is what keeps grounding "corpus-scoped" — only
     * manual chunks, never generic CRM activity/quote/email vectors or RE listing-disclosure vectors, can
     * ground a tech answer. Falls back to an empty flux if the vector index is unavailable — the copilot
     * then short-circuits to a handoff (never grounds on nothing; T13-D4).
     *
     * @param tenantId   the tenant scope — mandatory
     * @param question   the technician's question to embed as a query vector
     * @param sourceType the embedding source type that bounds the corpus (e.g. {@link #TECH_DOC_SOURCE_TYPE})
     * @param topK       candidates to pull from the index
     */
    public Flux<CorpusChunk> retrieveForCorpus(UUID tenantId, String question,
                                               String sourceType, int topK) {
        return embeddingService.embed(tenantId, question)
                .flatMapMany(vector -> vectorIndex.search(tenantId, vector, topK, null))
                .filter(hit -> hit.sourceId() != null)
                .filter(hit -> sourceType.equals(hit.sourceType()))
                .map(hit -> new CorpusChunk(
                        hit.sourceId(),
                        previewFrom(hit),
                        hit.score(),
                        hit.metadata() != null ? hit.metadata() : Map.of()))
                .onErrorResume(err -> {
                    log.warn("Corpus-scoped RAG retrieval failed for tenant {} sourceType {}: {}",
                            tenantId, sourceType, err.toString());
                    return Flux.empty();
                });
    }

    private static boolean matchesContact(VectorIndex.VectorSearchHit hit, UUID contactId) {
        Object val = hit.metadata() != null ? hit.metadata().get("contactId") : null;
        return contactId.toString().equals(val instanceof String ? val : (val != null ? val.toString() : null));
    }

    /** Mirror of {@link #matchesContact}/{@link #matchesDeal} on the {@code listingId} metadata (RE-1). */
    private static boolean matchesListing(VectorIndex.VectorSearchHit hit, UUID listingId) {
        Object val = hit.metadata() != null ? hit.metadata().get("listingId") : null;
        return listingId.toString().equals(val instanceof String ? val : (val != null ? val.toString() : null));
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
