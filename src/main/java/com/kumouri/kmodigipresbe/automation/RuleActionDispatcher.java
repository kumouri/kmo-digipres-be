package com.kumouri.kmodigipresbe.automation;

import com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscriptionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.servicehub.TicketPriority;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import com.kumouri.kmodigipresbe.model.request.SendTemplateRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.template.SmsTemplateRegistry;
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
    private final TwilioSmsService twilioSms;
    private final SmsTemplateRegistry smsTemplates;
    private final TicketRepository tickets;

    public Mono<Void> dispatch(RuleAction action, DomainEvent event) {
        return switch (action.getType()) {
            case SEND_EMAIL_TEMPLATE -> sendEmailTemplate(action, event);
            case CREATE_TASK -> createTask(action, event);
            case OUTBOUND_WEBHOOK -> outboundWebhook(action, event);
            case SEND_SMS -> sendSms(action, event);
            case ESCALATE_TICKET -> escalateTicket(event);
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

    /**
     * Phase 10e — SMS dispatch. Pulls the recipient phone from
     * {@code event.payload().get(params.toPhoneField)} and the body from the
     * {@link SmsTemplateRegistry}. Missing/blank/non-E.164 phone or missing
     * template name is logged and skipped (no error, no throw) — mirrors
     * {@link #sendEmailTemplate}'s skip-on-missing behavior so a rule with a
     * not-yet-resolvable recipient doesn't take down the rest of the rule's
     * action list.
     *
     * <p>v1 caveat: the registry returns the literal template name when no
     * matching template is registered, so any Mustache placeholders (e.g.
     * {@code {{contactFirstName}}}) ship as-is. Full template rendering is a
     * follow-up (see {@link SmsTemplateRegistry}).
     */
    private Mono<Void> sendSms(RuleAction action, DomainEvent event) {
        String templateName = stringParam(action, "templateName");
        String toPhoneField = stringParam(action, "toPhoneField");
        if (templateName == null || toPhoneField == null) {
            log.debug("Skipping SEND_SMS: missing templateName or toPhoneField");
            return Mono.empty();
        }
        Object phoneRaw = event.payload() == null
                ? null
                : event.payload().get(toPhoneField);
        if (phoneRaw == null) {
            log.debug("Skipping SEND_SMS: payload has no '{}' field", toPhoneField);
            return Mono.empty();
        }
        String phone = phoneRaw.toString();
        if (!isLikelyE164(phone)) {
            log.warn("Skipping SEND_SMS: '{}' is not E.164", phone);
            return Mono.empty();
        }
        String body = smsTemplates.resolve(templateName);
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(body)
                .build();
        return twilioSms.sendSms(req).then();
    }

    /**
     * Phase 13b — raises the ticket identified by {@code event.subjectId()} by one
     * priority level (LOW→MEDIUM→HIGH→URGENT, capped). No-ops if the subject is not
     * a known ticket ID or if it is already at URGENT.
     */
    private Mono<Void> escalateTicket(DomainEvent event) {
        if (event.subjectId() == null) {
            log.debug("Skipping ESCALATE_TICKET: no subjectId in event");
            return Mono.empty();
        }
        return tickets.findById(event.subjectId())
                .flatMap(ticket -> {
                    TicketPriority current = ticket.getPriority();
                    TicketPriority next = switch (current) {
                        case LOW -> TicketPriority.MEDIUM;
                        case MEDIUM -> TicketPriority.HIGH;
                        case HIGH, URGENT -> TicketPriority.URGENT;
                    };
                    if (next == current) return Mono.empty();
                    ticket.setPriority(next);
                    return tickets.save(ticket).then();
                })
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.debug("ESCALATE_TICKET: ticket {} not found", event.subjectId())));
    }

    /**
     * Loose E.164 check: leading '+' followed by 8+ digits. Twilio enforces
     * stricter validation server-side; this is just a sanity gate to skip
     * obviously malformed values before issuing a billable API call.
     */
    private static boolean isLikelyE164(String s) {
        if (s == null || s.length() < 9 || s.charAt(0) != '+') return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
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
