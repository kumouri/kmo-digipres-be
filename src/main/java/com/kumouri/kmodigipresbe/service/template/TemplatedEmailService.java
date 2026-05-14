package com.kumouri.kmodigipresbe.service.template;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.request.SendTemplateRequest;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.template.EmailTemplate;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TemplatedEmailService {

    private final EmailService emailService;
    private final EmailTemplateService templates;
    private final EmailTemplateRenderer renderer;
    private final ContactRepository contacts;
    private final ActivityRepository activities;

    @Value("${kmosf.mail.smtp.username:}")
    private String defaultFromAddress;

    public Mono<Boolean> send(SendTemplateRequest req) {
        return resolveTemplate(req)
                .zipWith(contacts.findById(req.getToContactId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Recipient contact not found", 1620, 404))))
                .flatMap(tuple -> {
                    EmailTemplate tpl = tuple.getT1();
                    Contact contact = tuple.getT2();
                    String toAddress = primaryEmail(contact);
                    if (toAddress == null) {
                        return Mono.error(new DigiPresBeException(
                                "Contact has no email channel", 1621, 400));
                    }
                    String from = resolveFrom(req, tpl);
                    if (from == null || from.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "No from-address resolved for templated send", 1622, 400));
                    }
                    Map<String, Object> vars = enrichVariables(req.getVariables(), contact);
                    String subject = renderer.render(tpl.getSubject(), vars);
                    String body = renderer.render(tpl.getBody(), vars);
                    SingleEmailCommunicationRequest sendReq = SingleEmailCommunicationRequest.builder()
                            .from(new EmailContact(from))
                            .to(new EmailContact(toAddress))
                            .subject(subject)
                            .body(body)
                            .build();
                    return emailService.sendSingleEmail(sendReq)
                            .flatMap(sent -> {
                                if (!Boolean.TRUE.equals(sent)) return Mono.just(false);
                                return logTimeline(contact, tpl, subject, body, from)
                                        .thenReturn(true);
                            });
                });
    }

    private Mono<EmailTemplate> resolveTemplate(SendTemplateRequest req) {
        if (req.getTemplateId() != null) return templates.findById(req.getTemplateId());
        if (req.getTemplateName() != null && !req.getTemplateName().isBlank()) {
            return templates.findByName(req.getTemplateName());
        }
        return Mono.error(new DigiPresBeException(
                "templateId or templateName is required", 1623, 400));
    }

    private String primaryEmail(Contact contact) {
        if (contact.getEmails() == null || contact.getEmails().isEmpty()) return null;
        EmailContact first = contact.getEmails().get(0);
        return first == null ? null : first.asString();
    }

    private String resolveFrom(SendTemplateRequest req, EmailTemplate tpl) {
        if (req.getFromAddressOverride() != null && !req.getFromAddressOverride().isBlank()) {
            return req.getFromAddressOverride();
        }
        if (tpl.getFromAddress() != null && !tpl.getFromAddress().isBlank()) {
            return tpl.getFromAddress();
        }
        return defaultFromAddress;
    }

    private Map<String, Object> enrichVariables(Map<String, Object> vars, Contact contact) {
        Map<String, Object> out = new HashMap<>(vars == null ? Map.of() : vars);
        out.putIfAbsent("firstName", contact.getFirstName());
        out.putIfAbsent("lastName", contact.getLastName());
        out.putIfAbsent("displayName", contact.getDisplayName());
        out.putIfAbsent("primaryEmail", primaryEmail(contact));
        return out;
    }

    private Mono<Activity> logTimeline(Contact contact, EmailTemplate tpl, String subject,
                                       String body, String from) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("to", primaryEmail(contact));
        payload.put("from", from);
        payload.put("templateId", tpl.getId().toString());
        payload.put("templateName", tpl.getName());
        return activities.save(Activity.builder()
                .type(ActivityType.EMAIL)
                .direction(ActivityDirection.OUTBOUND)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contact.getId())
                .summary(subject)
                .body(body)
                .occurredAt(Instant.now())
                .payload(payload)
                .build());
    }
}
