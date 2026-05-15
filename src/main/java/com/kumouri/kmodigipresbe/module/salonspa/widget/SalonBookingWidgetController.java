package com.kumouri.kmodigipresbe.module.salonspa.widget;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Anonymous public endpoint for salon booking widget submissions. Mirrors the
 * Phase 10e {@code ServiceRequestWidgetController} pattern:
 * <ol>
 *   <li>Verify the HMAC-signed widget token; assert {@code widgetType="salon-booking"}
 *       (mismatch → errorCode {@code 2911}, 401).</li>
 *   <li>Establish synthetic tenant context from the token's tenantId.</li>
 *   <li>Upsert the submitter as a Contact (by email, read-only if existing).</li>
 *   <li>Create a {@code Booking} in draft/pending state.</li>
 *   <li>Return booking id, status, and deposit information.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/public/widget/salon-booking")
@ConditionalOnProperty(prefix = "kmosf.modules.salon-spa", name = "enabled")
@RequiredArgsConstructor
public class SalonBookingWidgetController {

    public static final String WIDGET_TYPE = "salon-booking";

    private final PublicWidgetTokenService tokens;
    private final ContactRepository contacts;
    private final SalonBookingService bookingService;

    @PostMapping("/{token}")
    public Mono<SalonBookingResponseDTO> submit(
            @PathVariable String token,
            @Valid @RequestBody SalonBookingSubmissionDTO body) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> handleSubmission(claims, body));
    }

    private Mono<SalonBookingResponseDTO> handleSubmission(
            PublicWidgetToken claims, SalonBookingSubmissionDTO body) {
        if (!WIDGET_TYPE.equals(claims.widgetType())) {
            return Mono.error(new DigiPresBeException(
                    "Widget token type mismatch (expected '" + WIDGET_TYPE
                            + "', got '" + claims.widgetType() + "')",
                    2911, 401));
        }
        TenantContext anon = new TenantContext(
                claims.tenantId(), null, Set.of("PUBLIC_WIDGET"));
        return upsertContact(body)
                .flatMap(contact -> createBooking(contact, body)
                        .map(booking -> new SalonBookingResponseDTO(
                                contact.getId(),
                                booking.getId(),
                                booking.getStatus(),
                                booking.isDepositRequired(),
                                booking.getDepositAmount(),
                                booking.getDepositInvoiceId())))
                .contextWrite(TenantContextHolder.write(anon));
    }

    private Mono<Contact> upsertContact(SalonBookingSubmissionDTO body) {
        return TenantContextHolder.required().flatMap(ctx ->
                contacts.findByTenantAndEmailAddress(ctx.tenantId(), body.email())
                        .next()
                        .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(body)))));
    }

    private Mono<Booking> createBooking(Contact contact, SalonBookingSubmissionDTO body) {
        Booking booking = Booking.builder()
                .contactId(contact.getId())
                .serviceMenuItemId(body.serviceMenuItemId())
                .scheduledStart(body.preferredStart())
                .notes(body.notes())
                .build();
        return bookingService.create(booking);
    }

    private Contact buildContact(SalonBookingSubmissionDTO body) {
        String fn = trimToNull(body.firstName());
        String ln = trimToNull(body.lastName());
        String displayName;
        if (fn == null && ln == null) {
            displayName = body.email();
        } else {
            displayName = ((fn == null ? "" : fn) + " " + (ln == null ? "" : ln)).trim();
        }
        List<PhoneNumber> phones = new ArrayList<>();
        if (trimToNull(body.phone()) != null) {
            phones.add(PhoneNumber.builder().number(body.phone().trim()).build());
        }
        return Contact.builder()
                .type(ContactType.PERSON)
                .firstName(fn)
                .lastName(ln)
                .displayName(displayName)
                .emails(List.of(new EmailContact(body.email())))
                .phones(phones)
                .tags(Set.of("public-booking"))
                .build();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
