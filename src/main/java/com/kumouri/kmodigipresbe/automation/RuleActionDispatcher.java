package com.kumouri.kmodigipresbe.automation;

import com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscriptionRepository;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.request.SendTemplateRequest;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.template.TemplatedEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches a single {@link RuleAction} for a matching {@link DomainEvent}. Each
 * action is implemented as a method here so the rule engine stays declarative.
 *
 * <p>Errors bubble out so the engine can log them; one failed action does not
 * cancel the rest of the rule's action list (see {@link RuleEngine#dispatch}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleActionDispatcher {

    private final TemplatedEmailService templatedEmail;
    private final ActivityRepository activities;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryService webhooks;

    public Mono<Void> dispatch(RuleAction action, DomainEvent event) {
        return switch (action.getType()) {
            case SEND_EMAIL_TEMPLATE -> sendEmailTemplate(action, event);
            case CREATE_TASK -> createTask(action, event);
            case OUTBOUND_WEBHOOK -> outboundWebhook(action, event);
        };
    }

    private Mono<Void> sendEmailTemplate(RuleAction action, DomainEvent event) {
        String templateName = stringParam(action, "templateName");
        String toContactIdField = stringParam(action, "toContactIdField", "primaryContactId");
        Object recipientRaw = event.payload() == null
                ? null
                : event.payload().get(toContactIdField);
        if (templateName == null || recipientRaw == null) {
            log.debug("Skipping SEND_EMAIL_TEMPLATE: missing templateName or recipient");
            return Mono.empty();
        }
        UUID recipient;
        try {
            recipient = UUID.fromString(recipientRaw.toString());
        } catch (IllegalArgumentException ex) {
            log.warn("SEND_EMAIL_TEMPLATE: '{}' is not a UUID", recipientRaw);
            return Mono.empty();
        }
        SendTemplateRequest req = SendTemplateRequest.builder()
                .templateName(templateName)
                .toContactId(recipient)
                .variables(eventVariables(event))
                .build();
        return templatedEmail.send(req).then();
    }

    private Mono<Void> createTask(RuleAction action, DomainEvent event) {
        String summary = stringParam(action, "summary", "Auto-generated task");
        long dueAtSeconds = longParam(action, "dueAtSeconds", 0L);
        UUID subjectId = event.subjectId();
        SubjectType subjectType = inferSubjectType(event.type());
        Activity task = Activity.builder()
                .type(ActivityType.TASK)
                .direction(ActivityDirection.INTERNAL)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .summary(summary)
                .occurredAt(event.occurredAt())
                .dueAt(event.occurredAt().plusSeconds(dueAtSeconds))
                .build();
        return activities.save(task).then();
    }

    private Mono<Void> outboundWebhook(RuleAction action, DomainEvent event) {
        Object subIdRaw = action.getParams() == null ? null : action.getParams().get("subscriptionId");
        if (subIdRaw == null) {
            log.debug("Skipping OUTBOUND_WEBHOOK: no subscriptionId in params");
            return Mono.empty();
        }
        UUID subId;
        try {
            subId = UUID.fromString(subIdRaw.toString());
        } catch (IllegalArgumentException ex) {
            log.warn("OUTBOUND_WEBHOOK: '{}' is not a UUID", subIdRaw);
            return Mono.empty();
        }
        return subscriptions.findById(subId)
                .flatMap(sub -> webhooks.deliver(sub, event));
    }

    private Map<String, Object> eventVariables(DomainEvent event) {
        Map<String, Object> vars = new HashMap<>(event.payload() == null ? Map.of() : event.payload());
        vars.putIfAbsent("eventType", event.type());
        return vars;
    }

    private SubjectType inferSubjectType(String eventType) {
        if (eventType == null) return SubjectType.CONTACT;
        if (eventType.startsWith("deal.")) return SubjectType.DEAL;
        if (eventType.startsWith("company.")) return SubjectType.COMPANY;
        if (eventType.startsWith("contact.")) return SubjectType.CONTACT;
        return SubjectType.CONTACT;
    }

    private static String stringParam(RuleAction action, String key) {
        return stringParam(action, key, null);
    }

    private static String stringParam(RuleAction action, String key, String fallback) {
        Object v = action.getParams() == null ? null : action.getParams().get(key);
        return v == null ? fallback : v.toString();
    }

    private static long longParam(RuleAction action, String key, long fallback) {
        Object v = action.getParams() == null ? null : action.getParams().get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
