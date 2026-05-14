package com.kumouri.kmodigipresbe.extension;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomFieldValidationIT {

    @Autowired ContactRepository contacts;
    @Autowired FieldDefinitionRepository defs;

    @Test
    void requiredFieldMissing_rejected() {
        UUID tenantId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));

        defs.save(FieldDefinition.builder()
                        .tenantId(tenantId)
                        .entityType(EntityType.CONTACT)
                        .key("leadSource")
                        .label("Lead Source")
                        .type(FieldType.TEXT)
                        .required(true)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        Contact bad = Contact.builder()
                .type(ContactType.PERSON)
                .firstName("NoSource")
                .build();

        StepVerifier.create(contacts.save(bad).contextWrite(TenantContextHolder.write(ctx)))
                .expectError()
                .verify();
    }

    @Test
    void enumValueOutsideOptions_rejected() {
        UUID tenantId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));

        defs.save(FieldDefinition.builder()
                        .tenantId(tenantId)
                        .entityType(EntityType.CONTACT)
                        .key("tier")
                        .label("Tier")
                        .type(FieldType.ENUM)
                        .options(List.of("BRONZE", "SILVER", "GOLD"))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        Contact bad = Contact.builder()
                .type(ContactType.PERSON)
                .firstName("BadTier")
                .customFields(Map.of("tier", "PLATINUM"))
                .build();

        StepVerifier.create(contacts.save(bad).contextWrite(TenantContextHolder.write(ctx)))
                .expectError()
                .verify();
    }

    @Test
    void validCustomFields_persistRoundTrip() {
        UUID tenantId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));

        defs.save(FieldDefinition.builder()
                        .tenantId(tenantId)
                        .entityType(EntityType.CONTACT)
                        .key("tier")
                        .label("Tier")
                        .type(FieldType.ENUM)
                        .options(List.of("BRONZE", "SILVER", "GOLD"))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        Contact good = Contact.builder()
                .type(ContactType.PERSON)
                .firstName("GoodTier")
                .customFields(Map.of("tier", "GOLD"))
                .build();

        Contact saved = contacts.save(good).contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);

        Contact loaded = contacts.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCustomFields()).containsEntry("tier", "GOLD");
    }
}
