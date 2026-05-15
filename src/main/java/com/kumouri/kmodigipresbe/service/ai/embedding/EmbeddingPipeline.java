package com.kumouri.kmodigipresbe.service.ai.embedding;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Subscribes to {@link DomainEventPublisher} and indexes relevant entities as
 * embedding vectors in the {@link VectorIndex}.
 *
 * <p>Only a subset of event types trigger embedding:
 * <ul>
 *   <li>{@code ACTIVITY_LOGGED} — only for {@link ActivityType#NOTE} and
 *       {@link ActivityType#EMAIL} (not CALL, TASK, MEETING — those are usually
 *       short or structured and rarely contain freeform text worth indexing)</li>
 *   <li>{@code ATTACHMENT_CREATED} — filename + content-type used as the snippet
 *       (actual binary content is not fetched)</li>
 *   <li>{@code QUOTE_CREATED} — quote number + line-item titles</li>
 *   <li>{@code EMAIL_RECEIVED} — inbound email subject + text preview</li>
 * </ul>
 *
 * <p>Each pipeline step is fault-tolerant: a failure to embed one entity never
 * blocks downstream events and never propagates back to the caller that saved
 * the entity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingPipeline {

    static final int MAX_SNIPPET_CHARS = 8_000;
    static final int MAX_PREVIEW_CHARS = 500;

    private static final Set<String> HANDLED_TYPES = Set.of(
            DomainEventType.ACTIVITY_LOGGED,
            DomainEventType.ATTACHMENT_CREATED,
            DomainEventType.QUOTE_CREATED,
            DomainEventType.EMAIL_RECEIVED);

    private final DomainEventPublisher publisher;
    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final ActivityRepository activities;
    private final AttachmentRepository attachments;
    private final QuoteRepository quotes;
    private final InboxMessageRepository inboxMessages;

    @PostConstruct
    public void start() {
        publisher.stream()
                .filter(e -> HANDLED_TYPES.contains(e.type()))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(event -> index(event)
                        .onErrorResume(err -> {
                            log.warn("EmbeddingPipeline failed for event {}/{}: {}",
                                    event.type(), event.subjectId(), err.toString());
                            return Mono.empty();
                        }))
                .subscribe();
        log.info("EmbeddingPipeline subscribed to DomainEventPublisher");
    }

    Mono<Void> index(DomainEvent event) {
        TenantContext syntheticCtx = new TenantContext(event.tenantId(), null, Set.of("EMBEDDING"));
        return dispatch(event)
                .contextWrite(TenantContextHolder.write(syntheticCtx));
    }

    private Mono<Void> dispatch(DomainEvent event) {
        return switch (event.type()) {
            case DomainEventType.ACTIVITY_LOGGED -> indexActivity(event.tenantId(), event.subjectId());
            case DomainEventType.ATTACHMENT_CREATED -> indexAttachment(event.tenantId(), event.subjectId());
            case DomainEventType.QUOTE_CREATED -> indexQuote(event.tenantId(), event.subjectId());
            case DomainEventType.EMAIL_RECEIVED -> indexInboxMessage(event.tenantId(), event.subjectId());
            default -> Mono.empty();
        };
    }

    private Mono<Void> indexActivity(UUID tenantId, UUID activityId) {
        return activities.findById(activityId)
                .filter(a -> a.getType() == ActivityType.NOTE || a.getType() == ActivityType.EMAIL)
                .flatMap(activity -> {
                    String snippet = buildActivitySnippet(activity);
                    if (snippet.isBlank()) return Mono.empty();
                    Map<String, Object> meta = Map.of(
                            "contentPreview", truncate(snippet, MAX_PREVIEW_CHARS),
                            "title", activity.getSummary() != null ? activity.getSummary() : "");
                    return embedAndUpsert(tenantId, "Activity", activityId, snippet, meta);
                });
    }

    private Mono<Void> indexAttachment(UUID tenantId, UUID attachmentId) {
        return attachments.findById(attachmentId)
                .flatMap(att -> {
                    String snippet = (att.getFilename() != null ? att.getFilename() : "")
                            + (att.getContentType() != null ? " " + att.getContentType() : "");
                    if (snippet.isBlank()) return Mono.empty();
                    Map<String, Object> meta = Map.of(
                            "contentPreview", truncate(snippet, MAX_PREVIEW_CHARS),
                            "title", att.getFilename() != null ? att.getFilename() : "");
                    return embedAndUpsert(tenantId, "Attachment", attachmentId, snippet, meta);
                });
    }

    private Mono<Void> indexQuote(UUID tenantId, UUID quoteId) {
        return quotes.findById(quoteId)
                .flatMap(quote -> {
                    String snippet = buildQuoteSnippet(quote);
                    if (snippet.isBlank()) return Mono.empty();
                    Map<String, Object> meta = Map.of(
                            "contentPreview", truncate(snippet, MAX_PREVIEW_CHARS),
                            "title", quote.getQuoteNumber() != null ? quote.getQuoteNumber() : "");
                    return embedAndUpsert(tenantId, "Quote", quoteId, snippet, meta);
                });
    }

    private Mono<Void> indexInboxMessage(UUID tenantId, UUID messageId) {
        return inboxMessages.findById(messageId)
                .flatMap(msg -> {
                    String snippet = buildMessageSnippet(msg);
                    if (snippet.isBlank()) return Mono.empty();
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("contentPreview", truncate(snippet, MAX_PREVIEW_CHARS));
                    meta.put("title", msg.getSubject() != null ? msg.getSubject() : "");
                    return embedAndUpsert(tenantId, "InboxMessage", messageId, snippet, meta);
                });
    }

    private Mono<Void> embedAndUpsert(UUID tenantId, String sourceType, UUID sourceId,
                                      String snippet, Map<String, Object> metadata) {
        String truncated = truncate(snippet, MAX_SNIPPET_CHARS);
        return embeddingService.embed(tenantId, truncated)
                .flatMap(vector -> vectorIndex.upsert(tenantId, sourceType, sourceId, vector, metadata));
    }

    private static String buildActivitySnippet(Activity a) {
        StringBuilder sb = new StringBuilder();
        if (a.getSummary() != null) sb.append(a.getSummary()).append(' ');
        if (a.getBody() != null) sb.append(a.getBody());
        return sb.toString().trim();
    }

    private static String buildQuoteSnippet(Quote q) {
        StringBuilder sb = new StringBuilder();
        if (q.getQuoteNumber() != null) sb.append(q.getQuoteNumber()).append(' ');
        if (q.getLineItems() != null) {
            q.getLineItems().forEach(li -> {
                if (li.getDescription() != null) sb.append(li.getDescription()).append(' ');
            });
        }
        return sb.toString().trim();
    }

    private static String buildMessageSnippet(InboxMessage m) {
        StringBuilder sb = new StringBuilder();
        if (m.getSubject() != null) sb.append(m.getSubject()).append('\n');
        if (m.getTextBody() != null && !m.getTextBody().isBlank()) {
            sb.append(m.getTextBody());
        } else if (m.getHtmlBody() != null) {
            // Very rough HTML strip — just remove tags
            sb.append(m.getHtmlBody().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
        }
        return sb.toString().trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
