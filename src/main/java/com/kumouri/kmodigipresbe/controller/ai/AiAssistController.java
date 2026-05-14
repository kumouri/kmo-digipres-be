package com.kumouri.kmodigipresbe.controller.ai;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.ai.AiAssistService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * AI v1 endpoints.
 * <ul>
 *   <li>{@code POST /ai/summarize-timeline} — pulls the contact's last 50
 *       activities + emails and asks the AI for a coherent paragraph.</li>
 *   <li>{@code POST /ai/draft-reply} — drafts a reply from a thread payload
 *       supplied inline (Phase 9g's shared inbox plumbs this together
 *       end-to-end).</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiAssistController {

    private static final int TIMELINE_LIMIT = 50;

    private final AiAssistService ai;
    private final ActivityRepository activities;

    @PostMapping("/summarize-timeline")
    public Mono<AiAssistService.AiSummary> summarizeTimeline(@RequestBody SummarizeBody body) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> activities
                        .findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                ctx.tenantId(), SubjectType.CONTACT, body.contactId()))
                .take(TIMELINE_LIMIT)
                .map(AiAssistController::toTimelineEntry)
                .collectList()
                .flatMap(entries -> ai.summarizeTimeline(
                        new AiAssistService.SummarizeTimelineRequest(body.contactId(), entries)));
    }

    @PostMapping("/draft-reply")
    public Mono<AiAssistService.AiDraft> draftReply(@RequestBody DraftReplyBody body) {
        return ai.draftReply(new AiAssistService.DraftReplyRequest(
                body.threadSubject(),
                body.recentMessages() == null ? List.of() : body.recentMessages(),
                body.intent()));
    }

    private static AiAssistService.TimelineEntry toTimelineEntry(Activity activity) {
        return new AiAssistService.TimelineEntry(
                activity.getType() == null ? "OTHER" : activity.getType().name(),
                activity.getOccurredAt() == null ? "" : activity.getOccurredAt().toString(),
                activity.getSummary(),
                activity.getBody());
    }

    public record SummarizeBody(UUID contactId) {
    }

    public record DraftReplyBody(String threadSubject,
                                 List<AiAssistService.ThreadMessage> recentMessages,
                                 String intent) {
    }
}
