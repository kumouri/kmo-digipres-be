package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.audit.AuditEventWriter;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContactCrudService {

    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final AuditEventWriter auditor;

    public Flux<Contact> findAll() {
        return contacts.findAll();
    }

    public Mono<Contact> findById(UUID id) {
        return contacts.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Contact not found", 1100, 404)));
    }

    public Mono<Contact> create(Contact toCreate) {
        toCreate.setId(null);
        return contacts.save(toCreate);
    }

    public Mono<Contact> update(UUID id, Contact patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getFirstName() != null) existing.setFirstName(patch.getFirstName());
            if (patch.getLastName() != null) existing.setLastName(patch.getLastName());
            if (patch.getDisplayName() != null) existing.setDisplayName(patch.getDisplayName());
            if (patch.getType() != null) existing.setType(patch.getType());
            if (patch.getCompanyId() != null) existing.setCompanyId(patch.getCompanyId());
            if (patch.getEmails() != null) existing.setEmails(patch.getEmails());
            if (patch.getPhones() != null) existing.setPhones(patch.getPhones());
            if (patch.getAddresses() != null) existing.setAddresses(patch.getAddresses());
            if (patch.getTags() != null) existing.setTags(patch.getTags());
            if (patch.getOwnerId() != null) existing.setOwnerId(patch.getOwnerId());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return contacts.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        // Audit the delete BEFORE issuing it: Spring Data MongoDB has no reactive
        // delete callback, so DELETE events must be emitted explicitly. Loading the
        // entity first gives the audit a coherent {tenantId, entityType, entityId};
        // doing so under tenant context also ensures we never audit a cross-tenant
        // delete (the find returns empty and the chain short-circuits before the
        // actual deleteById fires).
        return findById(id)
                .flatMap(existing -> auditor.auditDelete(existing)
                        .then(contacts.deleteById(id)));
    }

    public Flux<Activity> timeline(UUID contactId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> activities
                        .findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                ctx.tenantId(), SubjectType.CONTACT, contactId));
    }
}
