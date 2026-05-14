package com.kumouri.kmodigipresbe.service.inbox;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Connects {@link MentionParser}'s extracted handles to actual users and emits
 * {@link DomainEventType#MENTION_CREATED} domain events that
 * {@link MentionNotificationListener} subscribes to.
 *
 * <p>Handle → user resolution: the handle is treated as the email local-part.
 * {@code @alice} matches a user whose email starts with {@code alice@}. The
 * first match wins (Phase 9g doesn't disambiguate multi-user prefixes — staff
 * usernames inside a tenant are expected to be unique by convention).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentionResolver {

    private final UserRepository users;
    private final DomainEventPublisher events;

    /**
     * Scans the activity's {@code summary + body} for mentions; resolves each
     * via {@link UserRepository}; publishes one MENTION_CREATED event per
     * resolved user. Returns Mono<Void> for chain composition.
     */
    public Mono<Void> processActivity(Activity activity) {
        if (activity == null || activity.getTenantId() == null) return Mono.empty();
        String haystack = ((activity.getSummary() == null ? "" : activity.getSummary())
                + "\n" + (activity.getBody() == null ? "" : activity.getBody()));
        List<String> handles = MentionParser.extract(haystack);
        if (handles.isEmpty()) return Mono.empty();
        return TenantContextHolder.required()
                .flatMapMany(ctx -> reactor.core.publisher.Flux.fromIterable(handles)
                        .flatMap(handle -> users
                                .findFirstByTenantIdAndEmailStartingWith(ctx.tenantId(), handle + "@")))
                .doOnNext(user -> publish(activity, user.getId(), user.getEmail()))
                .then();
    }

    private void publish(Activity activity, UUID mentionedUserId, String mentionedUserEmail) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("activityId", activity.getId() == null ? null : activity.getId().toString());
        payload.put("activitySummary", activity.getSummary() == null ? "" : activity.getSummary());
        payload.put("mentionedUserId", mentionedUserId.toString());
        payload.put("mentionedUserEmail", mentionedUserEmail);
        if (activity.getOwnerId() != null) {
            payload.put("byUserId", activity.getOwnerId().toString());
        }
        events.publish(DomainEvent.of(
                DomainEventType.MENTION_CREATED, activity.getTenantId(), activity.getId(), payload));
    }
}
