package com.kumouri.kmodigipresbe.service.ai.vector;

import com.kumouri.kmodigipresbe.model.ai.VectorDocument;
import com.kumouri.kmodigipresbe.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MongoDB Atlas Vector Search implementation of {@link VectorIndex}.
 *
 * <p>Uses the {@code $vectorSearch} aggregation stage which requires that
 * the Atlas cluster has a vector search index named {@code vector_cosine_idx}
 * configured on the {@code vector_index} collection with:
 * <ul>
 *   <li>field: {@code vector}, type: {@code vector}, dimensions: 1536,
 *       similarity: {@code cosine}</li>
 *   <li>filter: {@code { tenantId: 1 }} — Atlas uses this to accelerate the
 *       mandatory pre-filter, keeping inter-tenant vectors completely separated
 *       at the query level</li>
 * </ul>
 *
 * <p>The {@code tenantId} filter is always applied inside {@code $vectorSearch}
 * itself, not in a downstream {@code $match}. Atlas enforces that pre-filters
 * on indexed fields are cheaper than post-filters; more importantly, this means
 * the engine never even considers another tenant's vectors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MongoAtlasVectorIndex implements VectorIndex {

    private static final String COLLECTION = "vector_index";
    private static final String INDEX_NAME = "vector_cosine_idx";

    private final VectorDocumentRepository repository;
    private final ReactiveMongoTemplate mongo;

    @Override
    public Mono<Void> upsert(UUID tenantId, String sourceType, UUID sourceId,
                             float[] vector, Map<String, Object> metadata) {
        return repository.findByTenantIdAndSourceTypeAndSourceId(tenantId, sourceType, sourceId)
                .defaultIfEmpty(VectorDocument.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .sourceType(sourceType)
                        .sourceId(sourceId)
                        .build())
                .flatMap(doc -> {
                    doc.setVector(vector);
                    doc.setMetadata(metadata);
                    return repository.save(doc);
                })
                .doOnNext(d -> log.debug("Upserted vector for {}/{}/{}", tenantId, sourceType, sourceId))
                .then();
    }

    @Override
    public Flux<VectorSearchHit> search(UUID tenantId, float[] queryVector, int topK,
                                        @Nullable String keywordFilter) {
        List<Double> vectorList = toDoubleList(queryVector);

        Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", INDEX_NAME)
                .append("path", "vector")
                .append("queryVector", vectorList)
                .append("numCandidates", topK * 10)
                .append("limit", topK)
                .append("filter", new Document("tenantId",
                        new Document("$eq", tenantId.toString()))));

        Document addScoreStage = new Document("$addFields",
                new Document("score", new Document("$meta", "vectorSearchScore")));

        List<AggregationOperation> pipeline = new ArrayList<>();
        pipeline.add(ctx -> vectorSearchStage);
        pipeline.add(ctx -> addScoreStage);

        if (keywordFilter != null && !keywordFilter.isBlank()) {
            Document keywordMatch = new Document("$match",
                    new Document("metadata.contentPreview",
                            new Document("$regex", keywordFilter).append("$options", "i")));
            pipeline.add(ctx -> keywordMatch);
        }

        return mongo.aggregate(
                        Aggregation.newAggregation(pipeline),
                        COLLECTION,
                        Document.class)
                .map(MongoAtlasVectorIndex::toHit)
                .doOnError(err -> log.warn(
                        "Vector search failed for tenant {}: {}", tenantId, err.toString()));
    }

    private static VectorSearchHit toHit(Document doc) {
        String sourceType = doc.getString("sourceType");
        String sourceIdStr = doc.getString("sourceId");
        UUID sourceId = sourceIdStr != null ? UUID.fromString(sourceIdStr) : null;
        double score = doc.getDouble("score") != null ? doc.getDouble("score") : 0.0;

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");

        return new VectorSearchHit(sourceType, sourceId, score,
                metadata != null ? metadata : Map.of());
    }

    private static List<Double> toDoubleList(float[] vector) {
        List<Double> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add((double) v);
        }
        return list;
    }
}
