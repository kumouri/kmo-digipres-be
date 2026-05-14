package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ContactRepository extends TenantScopedReactiveMongoRepository<Contact, UUID> {

    Flux<Contact> findAllByTenantIdAndCompanyId(UUID tenantId, UUID companyId);

    /**
     * Look up contacts by tenant + an email channel address. The path is
     * {@code emails.email} (a flat String) since
     * {@code InternetAddressConverters} registers a writing converter that
     * persists {@code jakarta.mail.internet.InternetAddress} as its address
     * string. Pre-converter data used the nested {@code emails.email.address}
     * shape; the {@code $or} keeps both reachable until a one-time backfill
     * rewrites old docs.
     *
     * <p>An explicit {@code @Query} is used rather than a derived method name
     * to avoid Spring Data's camelCase parser guessing wrong.
     */
    @Query("{ 'tenantId': ?0, $or: [ { 'emails.email': ?1 }, { 'emails.email.address': ?1 } ] }")
    Flux<Contact> findByTenantAndEmailAddress(UUID tenantId, String emailAddress);
}
