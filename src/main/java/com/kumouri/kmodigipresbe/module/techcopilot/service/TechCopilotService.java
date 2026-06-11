package com.kumouri.kmodigipresbe.module.techcopilot.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.techcopilot.model.EquipmentType;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechQuery;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechQueryRepository;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tech Copilot (T13) — the strict-grounded answerer (the {@code ListingConciergeService} twin). Composes
 * the corpus-scoped RAG retrieval with the no-hallucination {@link TechCopilotAnswerService}, surfaces the
 * citations (deduped to one per source doc), and persists the Q&amp;A as a {@link TechQuery} (the
 * usefulness-signal log).
 *
 * <p>The flow (T13-D4), with the no-hallucination guarantees baked in:
 * <ol>
 *   <li>{@code retrieveForCorpus} → the cited manual chunks (corpus-scoped, source-type-guarded);</li>
 *   <li>if <strong>no chunks</strong> → short-circuit to a handoff — the model is NEVER called with an
 *       empty context (there is nothing to ground on), so it can never invent a procedure;</li>
 *   <li>else build the manual context and call {@link TechCopilotAnswerService#answer};</li>
 *   <li>if the model replies with the {@code HANDOFF} token → a handoff (no fabricated answer);</li>
 *   <li>if the model call fails (budget/upstream/missing-key 1200/1202/1203) → treated as a handoff
 *       (best-effort — never a fabricated or empty answer);</li>
 *   <li>else → a grounded answer carrying the citations (deduped by {@code techDocId}).</li>
 * </ol>
 * Every outcome persists a {@link TechQuery} row and emits {@code TECH_QUERY_ANSWERED}.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code TechCopilotAutoConfiguration} so it exists only when the
 * module is enabled.
 */
@Slf4j
public class TechCopilotService {

    /** Context is capped so the assembled prompt stays small/cheap (the {@code AskAiService} posture). */
    static final int MAX_CONTEXT_CHARS = 6_000;
    /** Cap doc-level citations surfaced on an answer (keep the UI focused on the top sources). */
    static final int MAX_CITATIONS = 5;

    private final RagRetrievalService retrieval;
    private final TechCopilotAnswerService answerService;
    private final TechQueryRepository queries;
    private final DomainEventPublisher events;
    private final int retrievalTopK;
    private final double minScore;
    private final String handoffMessage;

    public TechCopilotService(RagRetrievalService retrieval,
                              TechCopilotAnswerService answerService,
                              TechQueryRepository queries,
                              DomainEventPublisher events,
                              int retrievalTopK,
                              double minScore,
                              String handoffMessage) {
        this.retrieval = retrieval;
        this.answerService = answerService;
        this.queries = queries;
        this.events = events;
        this.retrievalTopK = retrievalTopK;
        this.minScore = minScore;
        this.handoffMessage = handoffMessage;
    }

    /**
     * The cited grounded answer to a technician's question over the tenant's manual corpus, persisted.
     *
     * @param handoff   true when there was nothing to ground on / the model could not answer from context
     * @param answer    the grounded answer when {@code !handoff}; the handoff line when {@code handoff}
     * @param citations the doc(s) the answer was grounded in (empty on a handoff), deduped by doc
     * @param queryId   the persisted {@link TechQuery} id (the feedback target)
     */
    public record CopilotAnswer(boolean handoff, String answer,
                                List<TechQuery.QueryCitation> citations, UUID queryId) {
    }

    /**
     * Answers {@code question} strictly from the tenant's indexed manual corpus and persists a
     * {@link TechQuery}. Never fabricates: no chunks → handoff (no model call); a {@code HANDOFF} token or
     * any AI failure → handoff. {@code 4494} if the question is blank.
     */
    public Mono<CopilotAnswer> ask(String question, EquipmentType equipmentTypeHint) {
        if (question == null || question.isBlank()) {
            return Mono.error(new DigiPresBeException("Question is required", 4494, 400));
        }
        String trimmed = question.trim();
        return TenantContextHolder.required().flatMap(ctx ->
                retrieval.retrieveForCorpus(ctx.tenantId(), trimmed,
                                RagRetrievalService.TECH_DOC_SOURCE_TYPE, retrievalTopK)
                        .collectList()
                        .flatMap(retrieved -> {
                            // Security AI-08: RAG grounding is no longer prompt-enforced only. Drop any
                            // retrieved chunk whose similarity score is below the configurable min-score
                            // gate (default 0.0 = off / opt-in); retrieval always returns topK, so a
                            // low-relevance hit otherwise grounds a confident but mis-cited answer. If
                            // nothing clears the gate this collapses to the no-chunks→handoff path below.
                            List<RagRetrievalService.CorpusChunk> chunks = aboveMinScore(retrieved);
                            if (chunks.isEmpty()) {
                                // No-chunks short-circuit — never call the model with an empty context
                                // (T13-D4); persist the handoff for the usefulness log.
                                log.debug("T13 copilot: no manual chunks above min-score {} for tenant {} "
                                        + "— handoff", minScore, ctx.tenantId());
                                return persist(ctx.tenantId(), trimmed, equipmentTypeHint,
                                        true, null, List.of());
                            }
                            // Citations reflect the RETRIEVED (and now min-score-cleared) source docs, not a
                            // post-hoc verification that the model actually used each one. They are
                            // "here is what this answer was grounded against", deduped to one per source doc.
                            List<TechQuery.QueryCitation> citations = dedupeByDoc(chunks);
                            String context = buildContext(chunks);
                            return answerService.answer(context, trimmed)
                                    .flatMap(text -> isHandoff(text)
                                            ? persist(ctx.tenantId(), trimmed, equipmentTypeHint,
                                                    true, null, List.of())
                                            : persist(ctx.tenantId(), trimmed, equipmentTypeHint,
                                                    false, text, citations))
                                    .onErrorResume(err -> {
                                        // Budget/upstream/missing-key (1200/1202/1203) → handoff, never a
                                        // fabricated answer (best-effort, the no-hallucination guarantee).
                                        log.warn("T13 copilot: answer call failed for tenant {} — "
                                                + "handoff: {}", ctx.tenantId(), err.toString());
                                        return persist(ctx.tenantId(), trimmed, equipmentTypeHint,
                                                true, null, List.of());
                                    });
                        }));
    }

    /** Flips the tech's usefulness rating on a persisted query. {@code 4493} if not found for the tenant. */
    public Mono<TechQuery> recordFeedback(UUID queryId, boolean helpful) {
        return TenantContextHolder.required().flatMap(ctx ->
                queries.findByIdAndTenantId(queryId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Tech query not found", 4493, 404)))
                        .flatMap(q -> queries.save(q.toBuilder().helpful(helpful).build())));
    }

    public Flux<TechQuery> recentQueries() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> queries.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId()));
    }

    private Mono<CopilotAnswer> persist(UUID tenantId, String question, EquipmentType hint,
                                        boolean handoff, String answer,
                                        List<TechQuery.QueryCitation> citations) {
        TechQuery toSave = TechQuery.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .question(question)
                .equipmentTypeHint(hint)
                .handoff(handoff)
                .answer(handoff ? null : answer)
                .citations(new ArrayList<>(citations))
                .build();
        return queries.save(toSave)
                .doOnNext(saved -> events.publish(DomainEvent.of(
                        DomainEventType.TECH_QUERY_ANSWERED, tenantId, saved.getId(),
                        Map.of("techQueryId", saved.getId(),
                                "handoff", handoff,
                                "citedDocCount", citations.size()))))
                .map(saved -> new CopilotAnswer(
                        handoff,
                        handoff ? handoffMessage : answer,
                        saved.getCitations(),
                        saved.getId()));
    }

    /** True when the model emitted the {@code HANDOFF} token (trim + case-insensitive). */
    private static boolean isHandoff(String text) {
        return text != null && TechCopilotAnswerService.HANDOFF_TOKEN.equalsIgnoreCase(text.trim());
    }

    /**
     * AI-08 grounding gate: keep only the chunks whose similarity score is {@code >= minScore} (retrieval
     * is score-ordered, so this preserves order). With the default {@code minScore == 0.0} every retrieved
     * chunk is kept (the gate is off / opt-in), so existing behavior — and the ITs — are unchanged; an
     * operator can raise it to drop low-relevance hits that would otherwise produce a confident, mis-cited
     * answer.
     */
    private List<RagRetrievalService.CorpusChunk> aboveMinScore(
            List<RagRetrievalService.CorpusChunk> chunks) {
        if (minScore <= 0.0) {
            return chunks;
        }
        List<RagRetrievalService.CorpusChunk> kept = new ArrayList<>(chunks.size());
        for (RagRetrievalService.CorpusChunk c : chunks) {
            if (c.score() >= minScore) {
                kept.add(c);
            }
        }
        return kept;
    }

    /**
     * Collapses the retrieved chunks into one citation per source doc (a tech wants "from the Carrier 58STA
     * manual", not "chunks 3,4,7"). Keys on the chunk's {@code techDocId} metadata (the doc identity each
     * chunk carries), keeps the first (highest-scoring, since retrieval is score-ordered) chunk's preview
     * for each doc, preserves the retrieval order (best doc first), and caps at {@link #MAX_CITATIONS}.
     */
    private static List<TechQuery.QueryCitation> dedupeByDoc(
            List<RagRetrievalService.CorpusChunk> chunks) {
        Map<String, TechQuery.QueryCitation> byDoc = new LinkedHashMap<>();
        for (RagRetrievalService.CorpusChunk c : chunks) {
            Map<String, Object> meta = c.metadata();
            String docIdStr = stringMeta(meta, "techDocId");
            // Fall back to the chunk id when the metadata is absent (defensive — should not happen for a
            // TechDoc-sourced hit), so a citation is never silently dropped.
            String key = docIdStr != null ? docIdStr : c.chunkId().toString();
            if (byDoc.containsKey(key)) {
                continue;
            }
            UUID techDocId = docIdStr != null ? safeUuid(docIdStr) : c.chunkId();
            byDoc.put(key, TechQuery.QueryCitation.builder()
                    .techDocId(techDocId)
                    .techDocTitle(stringMetaOrEmpty(meta, "techDocTitle"))
                    .equipmentType(stringMetaOrEmpty(meta, "equipmentType"))
                    .contentPreview(c.contentPreview() == null ? "" : c.contentPreview())
                    .score(c.score())
                    .build());
            if (byDoc.size() >= MAX_CITATIONS) {
                break;
            }
        }
        return new ArrayList<>(byDoc.values());
    }

    private static String stringMeta(Map<String, Object> meta, String key) {
        if (meta == null) {
            return null;
        }
        Object v = meta.get(key);
        return v != null ? v.toString() : null;
    }

    private static String stringMetaOrEmpty(Map<String, Object> meta, String key) {
        String v = stringMeta(meta, key);
        return v != null ? v : "";
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Builds the capped manual context (the {@code AskAiService.buildContext} shape). Security AI-04: each
     * retrieved chunk is untrusted reference data extracted from an ingested document, so it is wrapped in
     * an explicit {@code <excerpt>} delimiter — the {@link TechCopilotAnswerService} system/user prompt
     * frames the surrounding {@code <manual_context>} block as "data only, never instructions", and the
     * per-excerpt fence keeps an injected payload in one chunk from bleeding into the next.
     */
    private static String buildContext(List<RagRetrievalService.CorpusChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (RagRetrievalService.CorpusChunk chunk : chunks) {
            String preview = chunk.contentPreview() == null ? "" : chunk.contentPreview();
            String entry = "<excerpt>" + preview + "</excerpt>\n";
            if (sb.length() + entry.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            sb.append(entry);
        }
        return sb.toString();
    }
}
