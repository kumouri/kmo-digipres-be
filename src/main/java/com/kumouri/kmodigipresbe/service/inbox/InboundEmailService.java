package com.kumouri.kmodigipresbe.service.inbox;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.model.inbox.InboxThread;
import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
import com.kumouri.kmodigipresbe.repository.InboxThreadRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Ingests one inbound email into the shared inbox. Upserts an {@link InboxThread}
 * by {@code (tenantId, fromAddress, subjectNormalized)} — normalised meaning
 * lower-cased and with leading {@code Re:} / {@code Fwd:} prefixes stripped —
 * appends an {@link InboxMessage}, and publishes
 * {@link DomainEventType#EMAIL_RECEIVED}.
 *
 * <p>Phase 9g ships this as the receiver API; an IMAP poller built on
 * {@code spring-integration-mail} that calls {@link #ingest} per polled message
 * is the next deferred follow-up. Tests + the inbox UI can drive ingest
 * directly via this service in the meantime.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundEmailService {

    private final InboxThreadRepository threads;
    private final InboxMessageRepository messages;
    private final DomainEventPublisher events;

    public Mono<InboxMessage> ingest(InboundEmail raw) {
        String normalisedSubject = normaliseSubject(raw.subject());
        return TenantContextHolder.required()
                .flatMap(ctx -> threads.findByTenantIdAndFromAddressAndSubjectNormalized(
                                ctx.tenantId(), raw.from(), normalisedSubject)
                        .switchIfEmpty(Mono.defer(() -> threads.save(InboxThread.builder()
                                .fromAddress(raw.from())
                                .subjectNormalized(normalisedSubject)
                                .firstMessageAt(raw.receivedAt())
                                .lastMessageAt(raw.receivedAt())
                                .messageCount(0)
                                .status(InboxThread.Status.UNCLAIMED)
                                .build())))
                        .flatMap(thread -> appendMessage(thread, raw)));
    }

    private Mono<InboxMessage> appendMessage(InboxThread thread, InboundEmail raw) {
        thread.setLastMessageAt(raw.receivedAt());
        thread.setMessageCount(thread.getMessageCount() + 1);
        return threads.save(thread)
                .flatMap(savedThread -> {
                    InboxMessage message = InboxMessage.builder()
                            .threadId(savedThread.getId())
                            .messageId(raw.messageId())
                            .from(raw.from())
                            .to(raw.to())
                            .subject(raw.subject())
                            .htmlBody(raw.htmlBody())
                            .textBody(raw.textBody())
                            .receivedAt(raw.receivedAt() == null ? Instant.now() : raw.receivedAt())
                            .contactId(raw.contactId())
                            .build();
                    return messages.save(message)
                            .doOnNext(saved -> publishReceived(savedThread, saved));
                });
    }

    private void publishReceived(InboxThread thread, InboxMessage saved) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("threadId", thread.getId() == null ? null : thread.getId().toString());
        payload.put("messageId", saved.getMessageId());
        payload.put("from", saved.getFrom());
        payload.put("subject", saved.getSubject());
        events.publish(DomainEvent.of(
                DomainEventType.EMAIL_RECEIVED, thread.getTenantId(), saved.getId(), payload));
    }

    static String normaliseSubject(String subject) {
        if (subject == null || subject.isBlank()) return "";
        String s = subject.trim();
        // Strip leading "Re:", "Fwd:", "FW:" — repeated.
        while (true) {
            String lower = s.toLowerCase();
            if (lower.startsWith("re:") || lower.startsWith("fw:")) {
                s = s.substring(3).trim();
            } else if (lower.startsWith("fwd:")) {
                s = s.substring(4).trim();
            } else {
                break;
            }
        }
        return s.toLowerCase();
    }

    public record InboundEmail(
            java.util.UUID contactId,
            String messageId,
            String from,
            java.util.List<String> to,
            String subject,
            String htmlBody,
            String textBody,
            Instant receivedAt) {
    }
}
