package com.kumouri.kmodigipresbe.service.ai;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The single AI seam. Implementations talk to Anthropic, OpenAI, or any other
 * provider — Phase 9f ships Anthropic only via {@link AnthropicAiAssistService}.
 * Adding OpenAI later is a one-class change.
 *
 * <p>Per-call budget enforcement runs through {@link AiUsageRecorder}; impls
 * stay focused on prompt construction + provider API.
 */
public interface AiAssistService {

    Mono<AiSummary> summarizeTimeline(SummarizeTimelineRequest req);

    Mono<AiDraft> draftReply(DraftReplyRequest req);

    /**
     * Free-form question-answering over a pre-assembled context string.
     * Phase 11b: called by {@code AskAiService} after RAG retrieval assembles the
     * relevant context chunks. Implementations use the same model + budget pipeline
     * as {@link #summarizeTimeline}.
     */
    Mono<AiAnswer> ask(AskRequest req);

    record AskRequest(String context, String question) {
    }

    record AiAnswer(String text, long inputTokens, long outputTokens) {
    }

    record SummarizeTimelineRequest(UUID contactId, List<TimelineEntry> timeline) {
    }

    record TimelineEntry(String type, String at, String summary, String body) {
    }

    record AiSummary(String text, long inputTokens, long outputTokens) {
    }

    record DraftReplyRequest(String threadSubject, List<ThreadMessage> recentMessages, String intent) {
    }

    record ThreadMessage(String from, String at, String body) {
    }

    record AiDraft(String text, long inputTokens, long outputTokens) {
    }
}
