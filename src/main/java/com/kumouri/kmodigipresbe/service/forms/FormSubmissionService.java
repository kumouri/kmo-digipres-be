package com.kumouri.kmodigipresbe.service.forms;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.forms.FormDefinition;
import com.kumouri.kmodigipresbe.model.forms.FormField;
import com.kumouri.kmodigipresbe.model.forms.FormSubmission;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.forms.FormDefinitionRepository;
import com.kumouri.kmodigipresbe.repository.forms.FormSubmissionRepository;
import com.kumouri.kmodigipresbe.service.marketing.UtmCaptureService;
import com.kumouri.kmodigipresbe.service.sequence.SequenceCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processes public form widget submissions:
 * <ol>
 *   <li>Resolves the {@link FormDefinition} and validates required fields.</li>
 *   <li>Upserts a {@link Contact} from the email field if {@code onSubmit.createContact=true}.</li>
 *   <li>Captures UTM attribution via {@link UtmCaptureService} (first-touch only).</li>
 *   <li>Enrolls the contact in a sequence if {@code onSubmit.addToSequenceId} is set.</li>
 *   <li>Persists the {@link FormSubmission} and publishes {@link DomainEventType#FORM_SUBMITTED}.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormSubmissionService {

    private final FormDefinitionRepository formDefs;
    private final FormSubmissionRepository formSubmissions;
    private final ContactRepository contacts;
    private final SequenceCrudService sequences;
    private final UtmCaptureService utmCapture;
    private final DomainEventPublisher events;

    public Mono<FormSubmission> submit(UUID tenantId, UUID formId,
                                       Map<String, String> rawPayload,
                                       Map<String, String> utmParams,
                                       String landingPageSlug) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
        return formDefs.findById(formId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Form not found", 3110, 404)))
                .flatMap(form -> {
                    if (!tenantId.equals(form.getTenantId())) {
                        return Mono.error(new DigiPresBeException(
                                "Form does not belong to this tenant", 3111, 403));
                    }
                    Map<String, String> parsed = validateAndParse(form, rawPayload);
                    return resolveContact(tenantId, form, parsed, utmParams, landingPageSlug)
                            .flatMap(contact -> persistAndEnroll(
                                    tenantId, form, rawPayload, parsed, utmParams, contact));
                })
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Map<String, String> validateAndParse(FormDefinition form,
                                                  Map<String, String> payload) {
        Map<String, String> parsed = new HashMap<>();
        for (FormField field : form.getFields()) {
            String val = payload.get(field.getKey());
            if (field.isRequired() && (val == null || val.isBlank())) {
                throw new DigiPresBeException(
                        "Required field missing: " + field.getKey(), 3112, 400);
            }
            if (val != null) {
                parsed.put(field.getKey(), val.trim());
            }
        }
        return parsed;
    }

    private Mono<Contact> resolveContact(UUID tenantId, FormDefinition form,
                                          Map<String, String> parsed,
                                          Map<String, String> utmParams,
                                          String landingPageSlug) {
        if (!form.getOnSubmit().isCreateContact()) {
            return Mono.empty();
        }
        String email = findEmailField(form, parsed);
        if (email == null || email.isBlank()) {
            return Mono.empty();
        }
        return contacts.findByTenantAndEmailAddress(tenantId, email)
                .next()
                .switchIfEmpty(Mono.defer(() -> {
                    String firstName = parsed.get("firstName");
                    String lastName = parsed.get("lastName");
                    String displayName = buildDisplayName(firstName, lastName, email);
                    return contacts.save(Contact.builder()
                            .tenantId(tenantId)
                            .type(ContactType.PERSON)
                            .firstName(firstName)
                            .lastName(lastName)
                            .displayName(displayName)
                            .emails(List.of(new EmailContact(email)))
                            .tags(Set.of("web-form"))
                            .build());
                }))
                .flatMap(contact -> utmCapture.capture(contact, utmParams, landingPageSlug));
    }

    private Mono<FormSubmission> persistAndEnroll(UUID tenantId, FormDefinition form,
                                                   Map<String, String> rawPayload,
                                                   Map<String, String> parsed,
                                                   Map<String, String> utmParams,
                                                   Contact contact) {
        FormSubmission submission = FormSubmission.builder()
                .tenantId(tenantId)
                .formId(form.getId())
                .rawPayload(rawPayload)
                .parsedFields(parsed)
                .contactId(contact != null ? contact.getId() : null)
                .utmParams(utmParams)
                .build();

        return formSubmissions.save(submission)
                .flatMap(saved -> enrollIfNeeded(form, contact).thenReturn(saved))
                .doOnNext(saved -> emitFormSubmitted(saved));
    }

    private Mono<Void> enrollIfNeeded(FormDefinition form, Contact contact) {
        UUID sequenceId = form.getOnSubmit().getAddToSequenceId();
        if (sequenceId == null || contact == null) return Mono.empty();
        return sequences.enroll(sequenceId, contact.getId())
                .then()
                .onErrorResume(e -> {
                    log.warn("Sequence enrollment failed for form={} contact={}: {}",
                            form.getId(), contact.getId(), e.getMessage());
                    return Mono.empty();
                });
    }

    private void emitFormSubmitted(FormSubmission submission) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("submissionId", submission.getId());
        payload.put("formId", submission.getFormId());
        if (submission.getContactId() != null) {
            payload.put("contactId", submission.getContactId());
        }
        events.publish(DomainEvent.of(
                DomainEventType.FORM_SUBMITTED,
                submission.getTenantId(),
                submission.getId(),
                payload));
    }

    private static String findEmailField(FormDefinition form, Map<String, String> parsed) {
        return form.getFields().stream()
                .filter(f -> f.getType() == FormField.FieldType.EMAIL)
                .map(f -> parsed.get(f.getKey()))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(parsed.get("email"));
    }

    private static String buildDisplayName(String firstName, String lastName, String email) {
        if (firstName == null && lastName == null) return email;
        return ((firstName == null ? "" : firstName) + " "
                + (lastName == null ? "" : lastName)).trim();
    }
}
