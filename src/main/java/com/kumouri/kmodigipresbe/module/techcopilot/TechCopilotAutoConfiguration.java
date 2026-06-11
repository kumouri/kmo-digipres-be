package com.kumouri.kmodigipresbe.module.techcopilot;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.module.techcopilot.ingest.TechDocChunker;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDocRepository;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechQueryRepository;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechCopilotAnswerService;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechCopilotService;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechDocService;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Tech Copilot (T13) — a RAG-grounded, cited Q&amp;A assistant for field technicians over a per-tenant
 * corpus of equipment manuals / SOPs / spec sheets. Loaded only when
 * {@code kmosf.modules.techcopilot.enabled=true} (the {@code RealEstateAutoConfiguration} module template),
 * <strong>default OFF</strong> (no {@code matchIfMissing}).
 *
 * <p><strong>Blast radius zero.</strong> With the property absent/false no Tech Copilot bean exists, the
 * controllers are absent from the OpenAPI spec, and NMM / the RE concierge / every other tenant is
 * byte-identically unaffected. The module rides the shipped RAG / embedding / vector spine directly: it
 * reuses {@link EmbeddingService}, {@link VectorIndex}, {@link RagRetrievalService} (via the lone additive
 * {@code retrieveForCorpus} overload), {@link AiUsageRecorder}, and {@link IntegrationConnectionRepository}
 * — all empty-diff. The net-new is the corpus model + the chunk-and-embed ingest ({@link TechDocService})
 * and the strict no-hallucination tech-Q&amp;A surface ({@link TechCopilotAnswerService} +
 * {@link TechCopilotService}). Error band: <strong>4490-4519</strong>.
 *
 * <p>Beans are hand-constructed (not component-scanned) so the {@code @Value}-resolved config lands on the
 * factory params (the RE/ChairFill lesson). The {@code @RestController}s ({@code TechDocController},
 * {@code TechCopilotController}) ARE component-scanned but {@code @ConditionalOnProperty}-gated, so they are
 * absent from the OpenAPI spec when the module is off.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.techcopilot", name = "enabled")
public class TechCopilotAutoConfiguration {

    public static final String MODULE_KEY = "techcopilot";

    @Bean
    public ModuleDefinition techCopilotModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Tech Copilot", "0.1.0",
                List.of("TECH_DOC", "TECH_QUERY"));
    }

    // ── Corpus CRUD + the chunk-and-embed ingest (T13 §3) ─────────────────────────

    /**
     * Owns the {@code TechDoc} CRUD AND the chunk-and-embed ingest (the §3 crux). Uses the shared
     * {@link EmbeddingService} + {@link VectorIndex} directly (the {@code ListingDisclosureService} shape),
     * one upsert per chunk, carrying {@code techDocId}/{@code chunkIndex} metadata; a shrunk doc's stale
     * trailing chunk vectors are removed through the {@link VectorIndex#delete} seam.
     */
    @Bean
    public TechDocService techDocService(
            TechDocRepository docs,
            EmbeddingService embeddingService,
            VectorIndex vectorIndex,
            DomainEventPublisher events,
            @Value("${kmosf.techcopilot.chunk-size:" + TechDocChunker.DEFAULT_MAX_CHARS + "}") int chunkSize,
            @Value("${kmosf.techcopilot.chunk-overlap:" + TechDocChunker.DEFAULT_OVERLAP + "}") int chunkOverlap) {
        return new TechDocService(docs, embeddingService, vectorIndex, events,
                chunkSize, chunkOverlap);
    }

    // ── The strict-grounded copilot (T13-D4) ──────────────────────────────────────

    /**
     * The strict, no-hallucination Anthropic caller — a sibling of {@code ConciergeAnswerService}. Hand-built
     * so the {@code @Value}-resolved key/base-url/model land on the factory params.
     */
    @Bean
    public TechCopilotAnswerService techCopilotAnswerService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.techcopilot.answer-model:claude-haiku-4-5}") String answerModel,
            @Value("${kmosf.techcopilot.answer-system-prompt:}") String systemPromptOverride) {
        return new TechCopilotAnswerService(webClientBuilder, connections, usageRecorder,
                baseUrl, houseKey, answerModel, systemPromptOverride);
    }

    /**
     * The strict-grounded answerer: corpus-scoped retrieval → no-chunks short-circuit → strict Claude →
     * {@code HANDOFF} detection → cited answer (deduped by doc) → persisted {@code TechQuery}.
     */
    @Bean
    public TechCopilotService techCopilotService(
            RagRetrievalService retrieval,
            TechCopilotAnswerService answerService,
            TechQueryRepository queries,
            DomainEventPublisher events,
            @Value("${kmosf.techcopilot.retrieval-top-k:8}") int retrievalTopK,
            // AI-08: minimum similarity score a retrieved chunk must clear to ground an answer. Default 0.0
            // = OFF (opt-in) so existing grounding behavior is byte-unchanged; raise it (e.g. on a normalized
            // cosine index) to drop low-relevance hits → the no-chunks handoff rather than a mis-cited answer.
            @Value("${kmosf.techcopilot.min-score:0.0}") double minScore,
            @Value("${kmosf.techcopilot.handoff-message:I don't have that documented in the manuals on "
                    + "file. Check the OEM manual for this unit, or escalate to a senior tech.}")
            String handoffMessage) {
        return new TechCopilotService(retrieval, answerService, queries, events,
                retrievalTopK, minScore, handoffMessage);
    }
}
