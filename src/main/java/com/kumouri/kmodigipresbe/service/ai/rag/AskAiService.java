package com.kumouri.kmodigipresbe.service.ai.rag;

import com.kumouri.kmodigipresbe.service.ai.AiAssistService;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Answers a natural-language question by retrieving relevant context chunks
 * (RAG) and forwarding to the configured {@link AiAssistService}.
 *
 * <p>Budget is checked before the Claude call (not before embedding — the
 * embedding cost is handled inside {@link com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService}).
 * Both costs count against the same {@code aiBudgetUsd} cap.
 *
 * <p>Context is capped at 3 000 characters so the assembled prompt stays well
 * within Claude's token limit for Haiku.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskAiService {

    static final int MAX_CONTEXT_CHARS = 3_000;

    private final RagRetrievalService retrieval;
    private final AiAssistService aiAssist;
    private final AiUsageRecorder usageRecorder;

    public record Citation(String sourceType, UUID sourceId, String contentPreview, double score) {
    }

    public record AskResult(String answer, List<Citation> citations) {
    }

    /**
     * @param question       the user's question
     * @param scopeContactId restrict retrieval to this contact's data (pass {@code null} for global)
     * @param scopeDealId    restrict retrieval to this deal's data (pass {@code null} for global)
     */
    public Mono<AskResult> ask(String question,
                               @Nullable UUID scopeContactId,
                               @Nullable UUID scopeDealId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> askForTenant(ctx.tenantId(), question, scopeContactId, scopeDealId));
    }

    private Mono<AskResult> askForTenant(UUID tenantId, String question,
                                          UUID scopeContactId, UUID scopeDealId) {
        return retrieval.retrieve(tenantId, question, scopeContactId, scopeDealId)
                .collectList()
                .flatMap(chunks -> {
                    String context = buildContext(chunks);
                    List<Citation> citations = chunks.stream()
                            .map(c -> new Citation(c.sourceType(), c.sourceId(),
                                    c.contentPreview(), c.score()))
                            .toList();
                    return usageRecorder.checkBudget()
                            .then(aiAssist.ask(new AiAssistService.AskRequest(context, question)))
                            .flatMap(ans -> usageRecorder
                                    .record(ans.inputTokens(), ans.outputTokens(), null)
                                    .thenReturn(new AskResult(ans.text(), citations)));
                });
    }

    private static String buildContext(List<RagRetrievalService.RetrievedChunk> chunks) {
        if (chunks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (RagRetrievalService.RetrievedChunk chunk : chunks) {
            String entry = "[" + chunk.sourceType() + " " + chunk.sourceId() + "]: "
                    + chunk.contentPreview() + "\n";
            if (sb.length() + entry.length() > MAX_CONTEXT_CHARS) break;
            sb.append(entry);
        }
        return sb.toString();
    }
}
