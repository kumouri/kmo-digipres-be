package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AuditDiffComputerTest {

    @Test
    void identicalEntitiesProduceNoDiffs() {
        Contact a = sampleContact();
        Contact b = sampleContact();

        assertThat(AuditDiffComputer.diff(a, b)).isEmpty();
    }

    @Test
    void changedFirstNameProducesOneDiff() {
        Contact prior = sampleContact();
        Contact next = sampleContact();
        next.setFirstName("Renamed");

        List<FieldDiff> diffs = AuditDiffComputer.diff(prior, next);

        assertThat(diffs)
                .extracting(FieldDiff::field, FieldDiff::before, FieldDiff::after)
                .containsExactly(tuple("firstName", "Alice", "Renamed"));
    }

    @Test
    void ignoredPropertiesDoNotProduceDiffs() {
        Contact prior = sampleContact();
        prior.setVersion(1L);
        prior.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        prior.setUpdatedAt(Instant.parse("2024-01-01T00:00:00Z"));

        Contact next = sampleContact();
        // Force the ignored fields to differ; should NOT appear in the diff.
        next.setVersion(2L);
        next.setCreatedAt(Instant.parse("2024-02-02T00:00:00Z"));
        next.setUpdatedAt(Instant.parse("2024-02-02T00:00:00Z"));

        assertThat(AuditDiffComputer.diff(prior, next)).isEmpty();
    }

    @Test
    void nullSidesReturnEmptyDiff() {
        Contact c = sampleContact();
        assertThat(AuditDiffComputer.diff(null, c)).isEmpty();
        assertThat(AuditDiffComputer.diff(c, null)).isEmpty();
    }

    @Test
    void changedNestedListProducesDiff() {
        Contact prior = sampleContact();
        Contact next = sampleContact();
        next.setTags(java.util.Set.of("vip"));

        List<FieldDiff> diffs = AuditDiffComputer.diff(prior, next);

        assertThat(diffs)
                .extracting(FieldDiff::field)
                .containsExactly("tags");
    }

    private static Contact sampleContact() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        return Contact.builder()
                .id(id)
                .tenantId(tenant)
                .type(ContactType.PERSON)
                .firstName("Alice")
                .lastName("Example")
                .build();
    }
}
