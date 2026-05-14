package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.PublicContactRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.ContactCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Unauthenticated public endpoint for creating a {@link Contact} from a public
 * lead-capture form. Mirrors {@link PublicBookingController}'s shape: tenant is
 * resolved from the path slug, the request is permitted by the staff security
 * chain (see {@code SecurityConfig.staffSecurityFilterChain}'s
 * {@code /public/**} matcher), and the call writes a {@link TenantContext} into
 * the Reactor context for the duration of the save so the
 * {@code TenantStampingCallback} stamps the right tenant on the new document.
 *
 * <p>TODO: out of scope for this initial cut, pick up in a follow-up:
 * <ul>
 *   <li>Captcha / Turnstile / hCaptcha header verification.</li>
 *   <li>Per-tenant configurable rate limits (a setting on {@link Tenant}). Today
 *       the limit is a hard-coded {@code PublicContactRateLimitFilter}.</li>
 *   <li>Webhook fan-out on new public contact via {@code WebhookSubscription}.</li>
 *   <li>Auto-log an {@code Activity} of type {@code NOTE} containing the form's
 *       {@code message} field. For v1 the message is dropped on the floor; the
 *       simpler path keeps this controller dependency-free of {@code ActivityCrudService}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicContactController {

    private final TenantRepository tenants;
    private final ContactCrudService contacts;

    @PostMapping("/{tenantSlug}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Contact> create(@PathVariable String tenantSlug,
                                @Valid @RequestBody PublicContactRequest req) {
        if (isBlank(req.firstName()) && isBlank(req.lastName())) {
            return Mono.error(new DigiPresBeException(
                    "At least one of firstName or lastName must be provided",
                    1101, 400));
        }
        return resolveActiveTenant(tenantSlug).flatMap(tenant -> {
            TenantContext anon = new TenantContext(
                    tenant.getId(), null, Set.of("ANONYMOUS_PUBLIC_CONTACT"));
            Contact toSave = buildContact(req);
            return contacts.create(toSave)
                    .contextWrite(TenantContextHolder.write(anon));
        });
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

    private Contact buildContact(PublicContactRequest req) {
        String displayName = computeDisplayName(req);
        List<PhoneNumber> phones = isBlank(req.phone())
                ? List.of()
                : List.of(PhoneNumber.builder().number(req.phone()).build());
        return Contact.builder()
                .type(ContactType.PERSON)
                .firstName(emptyToNull(req.firstName()))
                .lastName(emptyToNull(req.lastName()))
                .displayName(displayName)
                .emails(List.of(new EmailContact(req.email())))
                .phones(phones)
                .tags(Set.of("public-contact"))
                .ownerId(null)
                .build();
    }

    private static String computeDisplayName(PublicContactRequest req) {
        String fn = req.firstName() == null ? "" : req.firstName().trim();
        String ln = req.lastName() == null ? "" : req.lastName().trim();
        String joined = (fn + " " + ln).trim();
        return joined.isEmpty() ? req.email() : joined;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
