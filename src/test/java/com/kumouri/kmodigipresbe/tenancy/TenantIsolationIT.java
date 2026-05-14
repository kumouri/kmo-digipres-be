package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that the {@link TenantStampingCallback} +
 * {@link TenantScopedSimpleReactiveMongoRepository} pair prevent cross-tenant reads.
 * <p>Each tenant is a synthetic UUID; we write data while one is in the Reactor Context
 * and confirm another tenant's repository view doesn't see it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantIsolationIT {

    @Autowired
    ContactRepository contacts;

    @Test
    void tenantBCannotReadTenantAsContact() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, UUID.randomUUID(), Set.of("STAFF"));
        TenantContext ctxB = new TenantContext(tenantB, UUID.randomUUID(), Set.of("STAFF"));

        Contact alice = Contact.builder()
                .type(ContactType.PERSON)
                .firstName("Alice")
                .lastName("Tenant-A")
                .build();

        Mono<Contact> savedAsA = contacts.save(alice)
                .contextWrite(TenantContextHolder.write(ctxA));

        Contact saved = savedAsA.block();
        assertThat(saved).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantA);

        // Tenant B should NOT see Alice via findById
        StepVerifier.create(
                        contacts.findById(saved.getId())
                                .contextWrite(TenantContextHolder.write(ctxB)))
                .verifyComplete();

        // Tenant A should see Alice
        StepVerifier.create(
                        contacts.findById(saved.getId())
                                .contextWrite(TenantContextHolder.write(ctxA)))
                .expectNextMatches(c -> c.getFirstName().equals("Alice"))
                .verifyComplete();
    }

    @Test
    void stampingRejectsForeignTenantId() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantOther = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, UUID.randomUUID(), Set.of("STAFF"));

        Contact poisoned = Contact.builder()
                .tenantId(tenantOther) // pre-stamped with someone else's tenant
                .firstName("Mallory")
                .build();

        StepVerifier.create(contacts.save(poisoned)
                        .contextWrite(TenantContextHolder.write(ctxA)))
                .expectError()
                .verify();
    }
}
