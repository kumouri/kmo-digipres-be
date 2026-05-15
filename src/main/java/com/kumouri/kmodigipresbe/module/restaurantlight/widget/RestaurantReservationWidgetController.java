package com.kumouri.kmodigipresbe.module.restaurantlight.widget;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.Reservation;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.ReservationStatus;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.ReservationRepository;
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
 * Phase 14 — anonymous public widget endpoint for reservation submissions
 * (catering tastings, private in-house events). The token in the path proves
 * which tenant issued the snippet; {@code PublicWidgetSecurityConfig}
 * ({@code @Order(-3)}) permits all exchanges on {@code /public/widget/**}, so
 * this controller handles all auth via {@link PublicWidgetTokenService#verify}.
 *
 * <p>Flow:
 * <ol>
 *   <li>Verify the token, asserting {@code widgetType="restaurant-reservation"}
 *       (mismatches reject with errorCode {@code 1400}, status 401).</li>
 *   <li>Establish a synthetic {@link TenantContext} so the
 *       {@code TenantStampingCallback} stamps Contact + Reservation correctly.</li>
 *   <li>Upsert the Contact by email + tenant (existing contacts are not overwritten
 *       from a public form).</li>
 *   <li>Create a {@link Reservation} in {@link ReservationStatus#PENDING}.</li>
 *   <li>Return both IDs.</li>
 * </ol>
 *
 * <p>This controller uses {@link ReservationRepository} directly (not
 * {@code ReservationService}) so it can boot when the module is enabled at the
 * server level even before a tenant has enabled the module — the token issuance
 * path is where the tenant-level gate is enforced.
 */
@Slf4j
@RestController
@RequestMapping("/public/widget/restaurant-reservation")
@ConditionalOnProperty(prefix = "kmosf.modules.restaurant-light", name = "enabled")
@RequiredArgsConstructor
public class RestaurantReservationWidgetController {

    public static final String WIDGET_TYPE = "restaurant-reservation";

    private final PublicWidgetTokenService tokens;
    private final ContactRepository contacts;
    private final ReservationRepository reservations;

    @PostMapping("/{token}")
    public Mono<ReservationSubmissionResponseDTO> submit(
            @PathVariable String token,
            @Valid @RequestBody ReservationSubmissionDTO body) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> handleSubmission(claims, body));
    }

    private Mono<ReservationSubmissionResponseDTO> handleSubmission(
            PublicWidgetToken claims, ReservationSubmissionDTO body) {
        if (!WIDGET_TYPE.equals(claims.widgetType())) {
            return Mono.error(new DigiPresBeException(
                    "Widget token type mismatch (expected '" + WIDGET_TYPE
                            + "', got '" + claims.widgetType() + "')",
                    1400, 401));
        }
        TenantContext anon = new TenantContext(
                claims.tenantId(), null, Set.of("PUBLIC_WIDGET"));
        return upsertContact(body)
                .flatMap(contact -> createPendingReservation(contact, body)
                        .map(res -> new ReservationSubmissionResponseDTO(
                                contact.getId(), res.getId())))
                .contextWrite(TenantContextHolder.write(anon));
    }

    private Mono<Contact> upsertContact(ReservationSubmissionDTO body) {
        return TenantContextHolder.required().flatMap(ctx ->
                contacts.findByTenantAndEmailAddress(ctx.tenantId(), body.email())
                        .next()
                        .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(body)))));
    }

    private Mono<Reservation> createPendingReservation(Contact contact,
                                                        ReservationSubmissionDTO body) {
        Reservation res = Reservation.builder()
                .contactId(contact.getId())
                .reservedAt(body.reservedAt())
                .partySize(body.partySize())
                .status(ReservationStatus.PENDING)
                .notes(annotateNotes(contact, body))
                .build();
        return reservations.save(res);
    }

    private Contact buildContact(ReservationSubmissionDTO body) {
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
                .tags(Set.of("public-reservation-widget"))
                .build();
    }

    private static String annotateNotes(Contact contact, ReservationSubmissionDTO body) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reservation submitted via public widget");
        sb.append(" (contactId=").append(contact.getId()).append(")");
        if (body.notes() != null && !body.notes().isBlank()) {
            sb.append("\n\n").append(body.notes());
        }
        return sb.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
