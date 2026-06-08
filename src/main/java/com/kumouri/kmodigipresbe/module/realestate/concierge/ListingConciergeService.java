package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — the strict-grounded answerer (RE-1 §6.5). Composes the listing-scoped
 * RAG retrieval with the no-hallucination {@link ConciergeAnswerService} and surfaces the citations.
 *
 * <p>The flow (RE-1 §6.5), with the no-hallucination guarantees baked in:
 * <ol>
 *   <li>{@code retrieveForListing} → the cited disclosure chunks (listing-scoped, source-type-guarded);</li>
 *   <li>if <strong>no chunks</strong> → short-circuit to {@code HANDOFF} — the model is NEVER called with
 *       an empty context (there is nothing to ground on), so it can never invent an answer;</li>
 *   <li>else build the disclosure context and call {@link ConciergeAnswerService#answer};</li>
 *   <li>if the model replies with the {@code HANDOFF} token (trim/case-insensitive) → a handoff answer;</li>
 *   <li>if the model call fails (budget/upstream/missing-key 1200/1202/1203) → treated as a handoff
 *       (best-effort — never a fabricated or empty answer);</li>
 *   <li>else → a grounded answer carrying the citations (sourceId/disclosureType/contentPreview/score)
 *       for the persisted transcript.</li>
 * </ol>
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} so it exists only when the
 * module is enabled.
 */
@Slf4j
public class ListingConciergeService {

    /** Context is capped so the assembled prompt stays small/cheap (the {@code AskAiService} posture). */
    static final int MAX_CONTEXT_CHARS = 3_000;

    private final RagRetrievalService retrieval;
    private final ConciergeAnswerService answerService;
    private final int retrievalTopK;

    public ListingConciergeService(RagRetrievalService retrieval,
                                   ConciergeAnswerService answerService,
                                   int retrievalTopK) {
        this.retrieval = retrieval;
        this.answerService = answerService;
        this.retrievalTopK = retrievalTopK;
    }

    /**
     * The cited grounded answer to a buyer's question about one listing.
     *
     * @param handoff   true when there was nothing to ground on / the model could not answer from context
     * @param answer    the SMS answer text when {@code !handoff}; null/ignored when {@code handoff}
     * @param citations the disclosure(s) the answer was grounded in (empty on a handoff)
     */
    public record ConciergeAnswer(boolean handoff, String answer, List<Citation> citations) {

        public static ConciergeAnswer handoffAnswer() {
            return new ConciergeAnswer(true, null, List.of());
        }

        public static ConciergeAnswer grounded(String answer, List<Citation> citations) {
            return new ConciergeAnswer(false, answer, citations);
        }
    }

    /**
     * One cited disclosure — the RE-1 §6.5 citation, carried through from the RAG chunk. The
     * {@code disclosureType} is read from the chunk's {@code contentPreview}-sibling metadata via the
     * source-type label; here the chunk only carries sourceType/sourceId/preview/score, so the
     * disclosure type label is the {@code sourceType} ("ListingDisclosure") — the human-friendly category
     * (ROOF/BASEMENT/...) is resolved from the persisted disclosure by the caller when it stamps the turn.
     */
    public record Citation(UUID disclosureId, String contentPreview, double score) {
    }

    /**
     * Answers {@code question} for {@code listingId} strictly from that listing's indexed disclosures.
     * Never fabricates: no chunks → handoff (no model call); a {@code HANDOFF} token or any AI failure →
     * handoff. Runs under the caller's tenant context.
     */
    public Mono<ConciergeAnswer> answer(UUID tenantId, UUID listingId, String question) {
        return retrieval.retrieveForListing(tenantId, question, listingId, retrievalTopK)
                .collectList()
                .flatMap(chunks -> {
                    if (chunks.isEmpty()) {
                        // No-chunks short-circuit — never call the model with an empty context (RE-1 §6.5).
                        log.debug("RE-1 concierge: no disclosure chunks for listing {} — HANDOFF", listingId);
                        return Mono.just(ConciergeAnswer.handoffAnswer());
                    }
                    String context = buildContext(chunks);
                    List<Citation> citations = chunks.stream()
                            .map(c -> new Citation(c.sourceId(), c.contentPreview(), c.score()))
                            .toList();
                    return answerService.answer(context, question)
                            .map(text -> isHandoff(text)
                                    ? ConciergeAnswer.handoffAnswer()
                                    : ConciergeAnswer.grounded(text, citations))
                            .onErrorResume(err -> {
                                // Budget/upstream/missing-key (1200/1202/1203) → handoff, never a
                                // fabricated answer (best-effort, the no-hallucination guarantee).
                                log.warn("RE-1 concierge: answer call failed for listing {} — HANDOFF: {}",
                                        listingId, err.toString());
                                return Mono.just(ConciergeAnswer.handoffAnswer());
                            });
                });
    }

    /** True when the model emitted the {@code HANDOFF} token (trim + case-insensitive). */
    private static boolean isHandoff(String text) {
        return text != null && ConciergeAnswerService.HANDOFF_TOKEN.equalsIgnoreCase(text.trim());
    }

    /** Builds the "[disclosure preview]\n…" context, capped (the {@code AskAiService.buildContext} shape). */
    private static String buildContext(List<RagRetrievalService.RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (RagRetrievalService.RetrievedChunk chunk : chunks) {
            String entry = "- " + chunk.contentPreview() + "\n";
            if (sb.length() + entry.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            sb.append(entry);
        }
        return sb.toString();
    }
}
