package com.kumouri.kmodigipresbe.service.ai;

import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RagRetrievalService}: top-K ordering, citation provenance,
 * and scope filtering.
 */
@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    @Mock EmbeddingService embeddingService;
    @Mock VectorIndex vectorIndex;
    @InjectMocks RagRetrievalService service;

    private static final float[] QUERY_VECTOR = new float[1536];
    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void retrieve_returnsHitsInScoreOrder() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(embeddingService.embed(eq(TENANT), anyString())).thenReturn(Mono.just(QUERY_VECTOR));
        when(vectorIndex.search(eq(TENANT), any(), anyInt(), isNull())).thenReturn(Flux.just(
                new VectorIndex.VectorSearchHit("Activity", id1, 0.92,
                        Map.of("contentPreview", "pricing Q3")),
                new VectorIndex.VectorSearchHit("Quote", id2, 0.75,
                        Map.of("contentPreview", "enterprise tier 20% off"))));

        StepVerifier.create(service.retrieve(TENANT, "pricing", null, null))
                .assertNext(chunk -> {
                    assertThat(chunk.sourceType()).isEqualTo("Activity");
                    assertThat(chunk.sourceId()).isEqualTo(id1);
                    assertThat(chunk.score()).isEqualTo(0.92);
                    assertThat(chunk.contentPreview()).isEqualTo("pricing Q3");
                })
                .assertNext(chunk -> {
                    assertThat(chunk.sourceType()).isEqualTo("Quote");
                    assertThat(chunk.score()).isEqualTo(0.75);
                })
                .verifyComplete();
    }

    @Test
    void retrieve_withContactScope_filtersToContactOnly() {
        UUID contactId = UUID.randomUUID();
        UUID otherActivityId = UUID.randomUUID();
        UUID contactActivityId = UUID.randomUUID();

        when(embeddingService.embed(eq(TENANT), anyString())).thenReturn(Mono.just(QUERY_VECTOR));
        when(vectorIndex.search(eq(TENANT), any(), anyInt(), isNull())).thenReturn(Flux.just(
                new VectorIndex.VectorSearchHit("Activity", contactActivityId, 0.90,
                        Map.of("contentPreview", "relevant", "contactId", contactId.toString())),
                new VectorIndex.VectorSearchHit("Activity", otherActivityId, 0.85,
                        Map.of("contentPreview", "other contact"))  // no contactId key
        ));

        StepVerifier.create(service.retrieve(TENANT, "question", contactId, null))
                .assertNext(chunk -> assertThat(chunk.sourceId()).isEqualTo(contactActivityId))
                .verifyComplete();
    }

    @Test
    void retrieve_whenVectorSearchFails_returnsEmpty() {
        when(embeddingService.embed(eq(TENANT), anyString())).thenReturn(Mono.just(QUERY_VECTOR));
        when(vectorIndex.search(eq(TENANT), any(), anyInt(), isNull()))
                .thenReturn(Flux.error(new RuntimeException("Atlas unavailable")));

        StepVerifier.create(service.retrieve(TENANT, "question", null, null))
                .verifyComplete();
    }

    @Test
    void retrieve_skipsHitsWithNullSourceId() {
        when(embeddingService.embed(eq(TENANT), anyString())).thenReturn(Mono.just(QUERY_VECTOR));
        when(vectorIndex.search(eq(TENANT), any(), anyInt(), isNull())).thenReturn(Flux.just(
                new VectorIndex.VectorSearchHit("Activity", null, 0.99, Map.of()),
                new VectorIndex.VectorSearchHit("Quote", UUID.randomUUID(), 0.80,
                        Map.of("contentPreview", "valid"))));

        StepVerifier.create(service.retrieve(TENANT, "q", null, null))
                .assertNext(chunk -> assertThat(chunk.sourceType()).isEqualTo("Quote"))
                .verifyComplete();
    }
}
