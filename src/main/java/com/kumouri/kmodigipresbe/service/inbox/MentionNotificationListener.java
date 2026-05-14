package com.kumouri.kmodigipresbe.service.inbox;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.communication.TransactionalEmailService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendRequest;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Subscribes to {@link DomainEventPublisher} and sends a notification email to
 * the mentioned user when a {@link DomainEventType#MENTION_CREATED} event
 * fires.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MentionNotificationListener {

    public static final String SYSTEM_ROLE = "MENTION_NOTIFIER";

    private final DomainEventPublisher publisher;
    private final UserRepository users;
    private final TransactionalEmailService transactionalEmail;

    @Value("${kmosf.mention.notification-from:no-reply@kmosolutionsfoundry.com}")
    private String defaultFrom;

    @PostConstruct
    public void start() {
        publisher.stream()
                .publishOn(Schedulers.parallel())
                .filter(e -> DomainEventType.MENTION_CREATED.equals(e.type()))
                .flatMap(this::handle)
                .onErrorContinue((err, evt) ->
                        log.warn("MentionNotificationListener dropped event {}: {}", evt, err.toString()))
                .subscribe();
        log.info("MentionNotificationListener subscribed to DomainEventPublisher");
    }

    Mono<Void> handle(DomainEvent event) {
        UUID mentionedUserId = parseUuid(event.payload().get("mentionedUserId"));
        if (mentionedUserId == null) return Mono.empty();
        TenantContext ctx = new TenantContext(event.tenantId(), null, Set.of(SYSTEM_ROLE));
        return users.findById(mentionedUserId)
                .flatMap(user -> {
                    if (user.getEmail() == null || user.getEmail().isBlank()) {
                        return Mono.empty();
                    }
                    String subject = "You were mentioned in KMOSF CRM";
                    Object activitySummary = event.payload().getOrDefault("activitySummary", "");
                    String text = "Hi " + (user.getDisplayName() == null ? "" : user.getDisplayName())
                            + ",\n\nYou were mentioned in an activity: \"" + activitySummary + "\".\n\n"
                            + "Open it in the CRM to follow up.\n";
                    return transactionalEmail.send(new TransactionalSendRequest(
                                    java.util.List.of(user.getEmail()),
                                    defaultFrom,
                                    subject,
                                    null,
                                    text,
                                    "mention-notification",
                                    Map.of("kmosf_user_id", user.getId().toString())))
                            .then();
                })
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private static UUID parseUuid(Object v) {
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
