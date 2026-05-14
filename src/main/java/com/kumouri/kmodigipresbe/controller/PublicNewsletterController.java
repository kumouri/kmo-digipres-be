package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.request.PublicNewsletterSubscribeRequest;
import com.kumouri.kmodigipresbe.model.response.NewsletterSubscriptionResponse;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.ContactCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Anonymous newsletter signup. Tenant resolved from the path slug; idempotent —
 * existing Contacts with the same email in the same tenant get their
 * {@code subscriptionTopics} merged additively and the {@code "newsletter"} tag
 * ensured.
 *
 * <p>Mirrors {@link PublicContactController}'s tenant-resolution + anonymous
 * {@link TenantContext} pattern. Rate-limited by {@link PublicContactRateLimitFilter}
 * (same bucket as {@code /contacts}).
 *
 * <p>Response is a minimal record — we explicitly do not return the saved
 * {@link Contact} document because it carries staff-internal fields
 * ({@code ownerId}, {@code customFields}) we should not expose to anonymous callers.
 *
 * <p>Pre-existing edge case (shared with {@code PublicContactController}): Mongo
 * {@code $eq} is case-sensitive on email lookup, so {@code Foo@Bar.com} and
 * {@code foo@bar.com} create two distinct Contacts. Not addressed here.
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicNewsletterController {

    private static final String NEWSLETTER_TAG = "newsletter";

    private final TenantRepository tenants;
    private final ContactRepository contacts;
    private final ContactCrudService contactCrud;

    @PostMapping("/{tenantSlug}/newsletter/subscribe")
    public Mono<ResponseEntity<NewsletterSubscriptionResponse>> subscribe(
            @PathVariable String tenantSlug,
            @Valid @RequestBody PublicNewsletterSubscribeRequest req) {
        return resolveActiveTenant(tenantSlug).flatMap(tenant -> {
            TenantContext anon = new TenantContext(
                    tenant.getId(), null, Set.of("ANONYMOUS_NEWSLETTER_SIGNUP"));
            Set<String> requestedTopics = req.topics() == null
                    ? Set.of()
                    : Set.copyOf(req.topics());

            Mono<ResponseEntity<NewsletterSubscriptionResponse>> work = contacts
                    .findByTenantAndEmailAddress(tenant.getId(), req.email())
                    .next()
                    .flatMap(existing -> mergeSubscription(existing, requestedTopics)
                            .map(updated -> ResponseEntity
                                    .status(HttpStatus.OK)
                                    .body(new NewsletterSubscriptionResponse(
                                            req.email(),
                                            updated.getSubscriptionTopics(),
                                            "already_subscribed"))))
                    .switchIfEmpty(Mono.defer(() -> createSubscription(req, requestedTopics)
                            .map(created -> ResponseEntity
                                    .status(HttpStatus.CREATED)
                                    .body(new NewsletterSubscriptionResponse(
                                            req.email(),
                                            created.getSubscriptionTopics(),
                                            "subscribed")))));

            return work.contextWrite(TenantContextHolder.write(anon));
        });
    }

    private Mono<Contact> mergeSubscription(Contact existing, Set<String> requestedTopics) {
        Set<String> mergedTopics = new HashSet<>(existing.getSubscriptionTopics() == null
                ? Set.of()
                : existing.getSubscriptionTopics());
        mergedTopics.addAll(requestedTopics);

        Set<String> mergedTags = new HashSet<>(existing.getTags() == null
                ? Set.of()
                : existing.getTags());
        mergedTags.add(NEWSLETTER_TAG);

        Contact patch = Contact.builder()
                .subscriptionTopics(mergedTopics)
                .tags(mergedTags)
                .build();
        return contactCrud.update(existing.getId(), patch);
    }

    private Mono<Contact> createSubscription(PublicNewsletterSubscribeRequest req,
                                             Set<String> requestedTopics) {
        String displayName = isBlank(req.firstName()) ? req.email() : req.firstName().trim();
        Contact toCreate = Contact.builder()
                .type(ContactType.PERSON)
                .firstName(emptyToNull(req.firstName()))
                .displayName(displayName)
                .emails(List.of(new EmailContact(req.email())))
                .tags(Set.of(NEWSLETTER_TAG))
                .subscriptionTopics(requestedTopics)
                .ownerId(null)
                .build();
        return contactCrud.create(toCreate);
    }

    private Mono<Tenant> resolveActiveTenant(String slug) {
        return tenants.findBySlug(slug)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Unknown tenant: " + slug, 1201, 404)))
                .flatMap(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE
                        ? Mono.just(t)
                        : Mono.error(new DigiPresBeException(
                                "Tenant is not active: " + slug, 1202, 404)));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
