package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ContactRepository extends TenantScopedReactiveMongoRepository<Contact, UUID> {

    Flux<Contact> findAllByTenantIdAndCompanyId(UUID tenantId, UUID companyId);

    Mono<Contact> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Look up contacts by tenant + an email channel address. The nested path is
     * {@code emails.email.address} because {@code EmailContact.email} is a
     * {@code jakarta.mail.internet.InternetAddress} which Mongo serializes as a sub-doc
     * with an {@code address} field. We use an explicit {@code @Query} rather than a
     * derived method name to avoid Spring Data's camelCase parser guessing wrong.
     */
    @Query("{ 'tenantId': ?0, 'emails.email.address': ?1 }")
    Flux<Contact> findByTenantAndEmailAddress(UUID tenantId, String emailAddress);
}
