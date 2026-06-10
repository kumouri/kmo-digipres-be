package com.kumouri.kmodigipresbe.module.techcopilot;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.techcopilot.model.EquipmentType;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDoc;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechDocService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tech Copilot (T13) — the ingest IT: a multi-window doc is chunked + embedded as source type
 * {@code "TechDoc"} (with {@code techDocId}/{@code chunkIndex}/{@code contentPreview} metadata), retrievable
 * via {@code RagRetrievalService.retrieveForCorpus}, with {@code indexedAt}/{@code chunkCount} stamped; a
 * re-index of shrunk text deletes the stale trailing chunk; a blank text degrades best-effort (no
 * indexing, {@code indexedAt} null, never thrown — 4492).
 *
 * <h2>How RAG is made deterministic (no Atlas / no OpenAI)</h2>
 * The {@code VectorIndex} is an in-memory {@code @Primary} {@link InMemoryVectorIndex} (the
 * {@code RealEstateConciergeIT} precedent); the {@code EmbeddingService} is the shared mock from
 * {@link TestcontainersConfiguration} (a zero vector). This exercises the genuine chunk-and-embed +
 * {@code retrieveForCorpus} source-type filter without a live vector engine.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TechDocIngestIT.InMemoryVectorIndexConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.techcopilot.enabled=true",
        "kmosf.techcopilot.chunk-size=400",
        "kmosf.techcopilot.chunk-overlap=80"
})
class TechDocIngestIT {

    @Autowired TechDocService techDocService;
    @Autowired RagRetrievalService retrieval;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InMemoryVectorIndex vectorIndex;

    private UUID tenantId;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), TechDoc.class).block();
        vectorIndex.clear();
        tenantId = UUID.randomUUID();
    }

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void create_chunksAndEmbeds_asTechDocSourceType_withDocMetadata_retrievable() {
        // ~1,200 chars at a 400-char window → multiple chunks.
        String body = ("Fault E3 reset: power off 30 seconds, clear the obstruction, press the rollout "
                + "reset button until it clicks, then restore power. ").repeat(6);
        TechDoc doc = create("Furnace Service Manual", EquipmentType.FURNACE, body);

        assertThat(doc.getIndexedAt()).as("indexedAt stamped after chunk+embed").isNotNull();
        assertThat(doc.getChunkCount()).as("multiple chunks for a multi-window doc").isGreaterThan(1);

        // Every chunk is stored as source type "TechDoc" carrying the doc id + chunk index + preview.
        List<InMemoryVectorIndex.Stored> stored = vectorIndex.allForTenant(tenantId);
        assertThat(stored).hasSize(doc.getChunkCount());
        assertThat(stored).allSatisfy(s -> {
            assertThat(s.sourceType()).isEqualTo(RagRetrievalService.TECH_DOC_SOURCE_TYPE);
            assertThat(s.metadata().get("techDocId")).isEqualTo(doc.getId().toString());
            assertThat(s.metadata().get("techDocTitle")).isEqualTo("Furnace Service Manual");
            assertThat(s.metadata().get("equipmentType")).isEqualTo("FURNACE");
            assertThat(s.metadata()).containsKey("chunkIndex");
            assertThat(s.metadata().get("contentPreview").toString()).contains("Fault E3");
        });

        // retrieveForCorpus returns the tenant's TechDoc chunks (the production source-type filter).
        List<RagRetrievalService.CorpusChunk> hits = retrieval.retrieveForCorpus(
                        tenantId, "how do I reset fault E3?",
                        RagRetrievalService.TECH_DOC_SOURCE_TYPE, 12)
                .collectList().block();
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(h ->
                assertThat(h.metadata().get("techDocId")).isEqualTo(doc.getId().toString()));
    }

    @Test
    void reindexShrunkText_deletesStaleTrailingChunks() {
        String big = "section ".repeat(300); // many chunks at a 400-char window
        TechDoc doc = create("Big Manual", EquipmentType.GENERAL, big);
        int firstCount = doc.getChunkCount();
        assertThat(firstCount).isGreaterThan(1);
        assertThat(vectorIndex.allForTenant(tenantId)).hasSize(firstCount);

        // Re-index with a much shorter text → fewer chunks; the stale trailing chunk vectors are removed.
        TechDoc shrunk = techDocService.update(doc.getId(), TechDoc.builder()
                        .text("Just one short paragraph now.").build())
                .contextWrite(TenantContextHolder.write(ctx())).block();

        assertThat(shrunk.getChunkCount()).isLessThan(firstCount);
        assertThat(vectorIndex.allForTenant(tenantId))
                .as("stale trailing chunk vectors deleted on a shrunk re-index")
                .hasSize(shrunk.getChunkCount());
    }

    @Test
    void blankText_isRejected_4491_onCreate() {
        // Create requires non-blank title+text → 4491 (a hard validation, not the best-effort 4492 path).
        try {
            techDocService.create(TechDoc.builder().title("T").text("   ").build())
                    .contextWrite(TenantContextHolder.write(ctx())).block();
            assertThat(false).as("expected 4491").isTrue();
        } catch (Exception ex) {
            assertThat(ex.getMessage()).contains("required");
        }
    }

    private TechDoc create(String title, EquipmentType type, String text) {
        return techDocService.create(TechDoc.builder()
                        .title(title).equipmentType(type).text(text).build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    // ── deterministic in-memory VectorIndex (the RealEstateConciergeIT precedent) ──

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryVectorIndexConfig {
        @Bean
        @Primary
        InMemoryVectorIndex inMemoryVectorIndex() {
            return new InMemoryVectorIndex();
        }
    }

    static class InMemoryVectorIndex implements VectorIndex {

        record Key(UUID tenantId, String sourceType, UUID sourceId) {
        }

        record Stored(String sourceType, UUID sourceId, Map<String, Object> metadata) {
        }

        private final Map<Key, Stored> store = new ConcurrentHashMap<>();

        void clear() {
            store.clear();
        }

        List<Stored> allForTenant(UUID tenantId) {
            return store.entrySet().stream()
                    .filter(e -> e.getKey().tenantId().equals(tenantId))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        @Override
        public reactor.core.publisher.Mono<Void> upsert(UUID tenantId, String sourceType, UUID sourceId,
                                                        float[] vector, Map<String, Object> metadata) {
            store.put(new Key(tenantId, sourceType, sourceId), new Stored(sourceType, sourceId, metadata));
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> delete(UUID tenantId, String sourceType, UUID sourceId) {
            store.remove(new Key(tenantId, sourceType, sourceId));
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Flux<VectorSearchHit> search(UUID tenantId, float[] queryVector,
                                                                   int topK, @Nullable String keywordFilter) {
            return reactor.core.publisher.Flux.fromIterable(store.entrySet().stream()
                    .filter(e -> e.getKey().tenantId().equals(tenantId))
                    .map(e -> new VectorSearchHit(e.getValue().sourceType(), e.getValue().sourceId(),
                            0.9, e.getValue().metadata()))
                    .limit(topK)
                    .toList());
        }
    }
}
