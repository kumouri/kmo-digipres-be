package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves the {@link Contact} linked to the current portal user via {@code User.contactId},
 * applying defensive cross-tenant guards. Every {@code /portal/me/...} endpoint that
 * answers in terms of "the caller's data" goes through this one chokepoint so the error
 * codes (1250/1251/1252) and tenant predicate stay consistent.
 */
@Component
@RequiredArgsConstructor
public class PortalLinkedContactResolver {

    private final UserRepository users;
    private final ContactRepository contacts;

    public Mono<Contact> resolve() {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (ctx.userId() == null) {
                return Mono.error(new DigiPresBeException("No user id in token", 1250, 401));
            }
            return users.findById(ctx.userId())
                    .switchIfEmpty(Mono.error(() ->
                            new DigiPresBeException("User not found", 1251, 404)))
                    .flatMap(user -> {
                        if (user.getContactId() == null) {
                            return Mono.error(new DigiPresBeException(
                                    "Portal user has no linked contact", 1252, 404));
                        }
                        return contacts.findByTenantIdAndId(ctx.tenantId(), user.getContactId())
                                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                        "Linked contact not found", 1252, 404)));
                    });
        });
    }
}
