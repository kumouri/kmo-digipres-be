package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.request.PortalProfileUpdateRequest;
import com.kumouri.kmodigipresbe.model.response.PortalProfileResponse;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Portal-authenticated "me" surface. Lives behind {@code PortalSecurityConfig}'s
 * authenticated section ({@code /portal/**} minus the explicit {@code permitAll} list),
 * so every method here is reachable only with a valid portal JWT — the JWT's
 * {@code userId}/{@code tenantId} claims are already on the Reactor context by the time
 * we run.
 *
 * <p>Both endpoints traverse {@code User → User.contactId → Contact}. A portal user
 * without a linked contact gets 404 (errorCode 1252) consistently, rather than an empty
 * response or a 200 with nulls — the FE treats this as "profile not yet provisioned."
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalProfileController {

    private final UserRepository users;
    private final ContactRepository contacts;

    @GetMapping("/profile")
    public Mono<PortalProfileResponse> getProfile() {
        return resolveLinkedContact()
                .map(PortalProfileController::toResponse);
    }

    @PutMapping("/profile")
    public Mono<PortalProfileResponse> updateProfile(@RequestBody PortalProfileUpdateRequest req) {
        return resolveLinkedContact()
                .flatMap(existing -> {
                    if (req.firstName() != null) existing.setFirstName(req.firstName());
                    if (req.lastName() != null) existing.setLastName(req.lastName());
                    if (req.displayName() != null) existing.setDisplayName(req.displayName());
                    if (req.phones() != null) existing.setPhones(req.phones());
                    if (req.addresses() != null) existing.setAddresses(req.addresses());
                    return contacts.save(existing);
                })
                .map(PortalProfileController::toResponse);
    }

    private Mono<Contact> resolveLinkedContact() {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (ctx.userId() == null) {
                return Mono.error(new DigiPresBeException("No user id in token", 1250, 401));
            }
            return users.findById(ctx.userId())
                    .switchIfEmpty(Mono.error(() ->
                            new DigiPresBeException("User not found", 1251, 404)))
                    .flatMap(user -> loadContact(ctx, user.getContactId()));
        });
    }

    private Mono<Contact> loadContact(TenantContext ctx, UUID contactId) {
        if (contactId == null) {
            return Mono.error(new DigiPresBeException(
                    "Portal user has no linked contact", 1252, 404));
        }
        return contacts.findByTenantIdAndId(ctx.tenantId(), contactId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Linked contact not found", 1252, 404)));
    }

    private static PortalProfileResponse toResponse(Contact c) {
        List<String> emails = c.getEmails() == null
                ? List.of()
                : c.getEmails().stream()
                        .map(EmailContact::asString)
                        .filter(Objects::nonNull)
                        .toList();
        return new PortalProfileResponse(
                c.getId() == null ? null : c.getId().toString(),
                c.getFirstName(),
                c.getLastName(),
                c.getDisplayName(),
                emails,
                c.getPhones() == null ? List.of() : c.getPhones(),
                c.getAddresses() == null ? List.of() : c.getAddresses());
    }
}
