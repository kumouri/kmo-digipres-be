package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ContactRepository extends TenantScopedReactiveMongoRepository<Contact, UUID> {

    Flux<Contact> findAllByTenantId(UUID tenantId);

    Flux<Contact> findAllByTenantIdAndCompanyId(UUID tenantId, UUID companyId);

    Mono<Contact> findByTenantIdAndId(UUID tenantId, UUID id);

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

    /**
     * Look up contacts by tenant + a phone-channel number. The path is
     * {@code phones.number} (the {@link com.kumouri.kmodigipresbe.model.contact.PhoneNumber}
     * record's {@code number} field). Used by the Phase 1 voicemail-to-lead pipeline to
     * find-or-create a Contact keyed on the caller's Twilio {@code From} number.
     *
     * <p>Strictly additive (new finder) — the existing email finder and all
     * {@code ContactCrudService} behaviour are unchanged. An explicit {@code @Query} is
     * used (rather than a derived method name) for symmetry with the email finder and to
     * carry the explicit {@code tenantId} predicate ({@code TenantScopedReactiveMongoRepository}
     * does NOT auto-scope derived finders).
     */
    @Query("{ 'tenantId': ?0, 'phones.number': ?1 }")
    Flux<Contact> findByTenantAndPhoneNumber(UUID tenantId, String phoneNumber);
}
