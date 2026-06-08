package com.kumouri.kmodigipresbe.module.chairfill.widget;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntryRepository;
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
 * ChairFill CF-3 — anonymous public endpoint for {@code salon-waitlist} join submissions. A verbatim
 * clone of {@link com.kumouri.kmodigipresbe.module.salonspa.widget.SalonBookingWidgetController}:
 * <ol>
 *   <li>Verify the HMAC-signed widget token; assert {@code widgetType="salon-waitlist"} (mismatch →
 *       errorCode {@code 4230}, 401 — the CF-3 band; the generic token-rejection codes
 *       {@code 1600-1603} from {@code PublicWidgetTokenService} are surfaced unchanged for
 *       missing/malformed/bad-signature/expired tokens).</li>
 *   <li>Establish synthetic tenant context from the token's tenantId
 *       ({@code Set.of("PUBLIC_WIDGET")}).</li>
 *   <li>Upsert the submitter as a Contact (by email; an existing contact is returned untouched — we
 *       don't rewrite staff-curated data from a public form, the salon-booking-widget precedent).</li>
 *   <li>Create an {@code OPEN} {@link WaitlistEntry} (the join IS the SMS opt-in — plan §4 TCPA).</li>
 *   <li>Return the contact id, entry id, and status.</li>
 * </ol>
 *
 * <p>{@code @ConditionalOnProperty}-gated on {@code kmosf.modules.chairfill.enabled} (so it is absent
 * from the generated OpenAPI spec when the module is off — the {@code NoShowRiskController} /
 * Home-Services precedent), and reached via the {@code /public/**} permitAll rule.
 */
@Slf4j
@RestController
@RequestMapping("/public/widget/salon-waitlist")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@RequiredArgsConstructor
public class WaitlistWidgetController {

    public static final String WIDGET_TYPE = "salon-waitlist";

    private final PublicWidgetTokenService tokens;
    private final ContactRepository contacts;
    private final WaitlistEntryRepository waitlist;

    @PostMapping("/{token}")
    public Mono<WaitlistJoinResponseDTO> submit(
            @PathVariable String token,
            @Valid @RequestBody WaitlistJoinSubmissionDTO body) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> handleSubmission(claims, body));
    }

    private Mono<WaitlistJoinResponseDTO> handleSubmission(
            PublicWidgetToken claims, WaitlistJoinSubmissionDTO body) {
        if (!WIDGET_TYPE.equals(claims.widgetType())) {
            return Mono.error(new DigiPresBeException(
                    "Widget token type mismatch (expected '" + WIDGET_TYPE
                            + "', got '" + claims.widgetType() + "')",
                    4230, 401));
        }
        TenantContext anon = new TenantContext(
                claims.tenantId(), null, Set.of("PUBLIC_WIDGET"));
        return upsertContact(body)
                .flatMap(contact -> createEntry(contact, body)
                        .map(entry -> new WaitlistJoinResponseDTO(
                                contact.getId(),
                                entry.getId(),
                                entry.getStatus())))
                .contextWrite(TenantContextHolder.write(anon));
    }

    private Mono<Contact> upsertContact(WaitlistJoinSubmissionDTO body) {
        return TenantContextHolder.required().flatMap(ctx ->
                contacts.findByTenantAndEmailAddress(ctx.tenantId(), body.email())
                        .next()
                        .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(body)))));
    }

    private Mono<WaitlistEntry> createEntry(Contact contact, WaitlistJoinSubmissionDTO body) {
        WaitlistEntry entry = WaitlistEntry.builder()
                .contactId(contact.getId())
                .serviceMenuItemId(trimToNull(body.serviceMenuItemId()))
                .preferredStaffMemberId(body.preferredStaffMemberId())
                .earliestStart(body.earliestStart())
                .latestStart(body.latestStart())
                .smsOptIn(true) // joining the waitlist to be texted about openings is the consent act
                .status(WaitlistEntry.Status.OPEN)
                .notes(trimToNull(body.notes()))
                .build();
        return waitlist.save(entry);
    }

    private Contact buildContact(WaitlistJoinSubmissionDTO body) {
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
            phones.add(PhoneNumber.builder().number(body.phone().trim()).label("mobile").build());
        }
        return Contact.builder()
                .type(ContactType.PERSON)
                .firstName(fn)
                .lastName(ln)
                .displayName(displayName)
                .emails(List.of(new EmailContact(body.email())))
                .phones(phones)
                .tags(Set.of("salon-waitlist"))
                .build();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
