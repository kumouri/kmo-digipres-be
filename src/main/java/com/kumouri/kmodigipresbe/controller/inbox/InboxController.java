package com.kumouri.kmodigipresbe.controller.inbox;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.model.inbox.InboxThread;
import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
import com.kumouri.kmodigipresbe.repository.InboxThreadRepository;
import com.kumouri.kmodigipresbe.service.communication.TransactionalEmailService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendRequest;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared inbox read + claim + reply endpoints. Ingest happens via
 * {@code InboundEmailService.ingest} (called by the deferred IMAP poller, or
 * an internal admin API).
 */
@RestController
@RequestMapping("/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxThreadRepository threads;
    private final InboxMessageRepository messages;
    private final TransactionalEmailService transactionalEmail;

    @GetMapping("/threads")
    public Flux<InboxThread> listThreads() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> threads.findAllByTenantIdOrderByLastMessageAtDesc(ctx.tenantId()));
    }

    @GetMapping("/threads/{id}")
    public Mono<InboxThread> getThread(@PathVariable UUID id) {
        return threads.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "InboxThread not found", 2300, 404)));
    }

    @GetMapping("/threads/{id}/messages")
    public Flux<InboxMessage> messagesFor(@PathVariable UUID id) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> messages
                        .findAllByTenantIdAndThreadIdOrderByReceivedAtAsc(ctx.tenantId(), id));
    }

    @PostMapping("/threads/{id}/claim")
    public Mono<InboxThread> claim(@PathVariable UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> threads.findById(id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "InboxThread not found", 2300, 404)))
                        .flatMap(thread -> {
                            thread.setStatus(InboxThread.Status.CLAIMED);
                            thread.setClaimedByUserId(ctx.userId());
                            return threads.save(thread);
                        }));
    }

    @PostMapping("/threads/{id}/messages")
    public Mono<Map<String, Object>> reply(@PathVariable UUID id, @RequestBody ReplyBody body) {
        return threads.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "InboxThread not found", 2300, 404)))
                .flatMap(thread -> transactionalEmail.send(new TransactionalSendRequest(
                                List.of(thread.getFromAddress()),
                                body.from() == null ? "no-reply@kmosolutionsfoundry.com" : body.from(),
                                "Re: " + thread.getSubjectNormalized(),
                                body.htmlBody(),
                                body.textBody(),
                                "inbox-reply",
                                Map.of("kmosf_thread_id", thread.getId().toString())))
                        .map(result -> Map.of(
                                "messageId", (Object) result.messageId(),
                                "submittedAt", result.submittedAt().toString())));
    }

    public record ReplyBody(String from, String htmlBody, String textBody) {
    }
}
