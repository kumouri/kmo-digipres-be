package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.ContactCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks for {@link AuditingCallback} + {@link AuditEventWriter}:
 * each Auditable save / delete produces an {@link AuditEvent} with the expected
 * shape, scoped to the active tenant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditingCallbackIT {

    @Autowired ContactRepository contacts;
    @Autowired ContactCrudService contactCrud;
    @Autowired AuditEventRepository auditEvents;
    @Autowired ReactiveMongoTemplate mongo;

    @BeforeEach
    void clean() {
        // Shared Mongo container — wipe collections this IT touches so prior runs
        // don't pollute the audit-event count assertions.
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), AuditEvent.class).block();
    }

    @Test
    void createContact_writesCreateAuditEvent() {
        UUID tenantA = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, actor, Set.of("STAFF"));

        Contact saved = contacts.save(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("Alice")
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                        tenantA, "Contact", saved.getId())
                .collectList()
                .block();
        assertThat(events).hasSize(1);
        AuditEvent evt = events.get(0);
        assertThat(evt.getOp()).isEqualTo(AuditOp.CREATE);
        assertThat(evt.getActorUserId()).isEqualTo(actor);
        assertThat(evt.getFieldDiffs()).isEmpty();
        assertThat(evt.getAt()).isNotNull();
    }

    @Test
    void updateContact_writesUpdateAuditEventWithDiff() {
        UUID tenantA = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, actor, Set.of("STAFF"));

        Contact saved = contacts.save(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("Alice")
                        .lastName("Example")
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        saved.setFirstName("Renamed");
        contacts.save(saved)
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                        tenantA, "Contact", saved.getId())
                .collectList()
                .block();
        assertThat(events).hasSize(2);
        AuditEvent latest = events.get(0); // ordered desc
        assertThat(latest.getOp()).isEqualTo(AuditOp.UPDATE);
        assertThat(latest.getFieldDiffs())
                .extracting(FieldDiff::field)
                .contains("firstName");
        FieldDiff firstNameDiff = latest.getFieldDiffs().stream()
                .filter(d -> "firstName".equals(d.field()))
                .findFirst().orElseThrow();
        assertThat(firstNameDiff.before()).isEqualTo("Alice");
        assertThat(firstNameDiff.after()).isEqualTo("Renamed");
    }

    @Test
    void deleteContact_writesDeleteAuditEvent() {
        UUID tenantA = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, actor, Set.of("STAFF"));

        Contact saved = contactCrud.create(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("ToDelete")
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        contactCrud.delete(saved.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();

        List<AuditEvent> events = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                        tenantA, "Contact", saved.getId())
                .collectList()
                .block();
        // Expect CREATE then DELETE.
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getOp()).isEqualTo(AuditOp.DELETE);
        assertThat(events.get(1).getOp()).isEqualTo(AuditOp.CREATE);
    }

    @Test
    void tenantBCannotReadTenantAsAuditEvents() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, UUID.randomUUID(), Set.of("STAFF"));

        Contact saved = contacts.save(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("Confidential")
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        List<AuditEvent> bView = auditEvents.findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                        tenantB, "Contact", saved.getId())
                .collectList()
                .block();
        assertThat(bView).isEmpty();
    }

    @Test
    void ttlIndexIsConfiguredOnAtField() {
        // Ensure the TTL index exists on audit_events.at with the expected 730d
        // expireAfterSeconds. Triggers Mongo to create the index lazily — touch
        // the collection first via a write of a throwaway event under a fresh tenant.
        UUID tenant = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenant, UUID.randomUUID(), Set.of("STAFF"));
        contacts.save(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("TriggerIndex")
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        List<org.bson.Document> indexes = mongo.getCollection("audit_events")
                .flatMapMany(c -> c.listIndexes())
                .collectList()
                .block();
        assertThat(indexes).isNotNull();
        org.bson.Document ttl = indexes.stream()
                .filter(d -> "audit_at_ttl_idx".equals(d.getString("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "audit_at_ttl_idx index not found; saw: " + indexes));
        // Spring Data passes expireAfter as seconds.
        assertThat(ttl.getLong("expireAfterSeconds"))
                .isEqualTo(730L * 24 * 60 * 60);
        assertThat(((org.bson.Document) ttl.get("key")).keySet())
                .containsExactly("at");
    }
}
