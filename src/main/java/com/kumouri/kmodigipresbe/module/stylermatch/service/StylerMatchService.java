package com.kumouri.kmodigipresbe.module.stylermatch.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.stylermatch.model.MatchRequest;
import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;
import com.kumouri.kmodigipresbe.module.stylermatch.repository.StylerMatchRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the match orchestrator: turns a requested service/style into a ranked,
 * explained stylist board persisted as a {@link StylerMatch}. The stylist-side twin of the T9
 * {@code StyleConsultService} — it composes the tenant's stylists + menus (+ the contact's COMPLETED
 * bookings) with the <strong>pure, deterministic</strong> {@link StylerMatchScoringService}; there is
 * <strong>no LLM call</strong> (a text/attribute match needs none).
 *
 * <h2>Two entry points</h2>
 * <ul>
 *   <li>{@link #submitPublic} — the public widget: verify the {@link PublicWidgetTokenService} token
 *       ({@code styler-match} widgetType; tenant from the token <strong>only</strong>, never the body —
 *       a stranger cannot match against another tenant), establish a synthetic
 *       {@code TenantContext(tenantId, null, PUBLIC_WIDGET)}, then run the pipeline. The T9
 *       {@code StyleConsultService.submit} shape.</li>
 *   <li>{@link #createForTenant} — the staff desk: the caller is already in a {@code TenantContext}; run
 *       the pipeline for that tenant.</li>
 * </ul>
 *
 * <h2>Lead</h2>
 * The client is found-or-created as a {@code Contact} by phone &rarr; email (explicit-boolean — an
 * existing contact is reused untouched, never {@code switchIfEmpty(create)}; the T9
 * {@code StyleConsultService} precedent) when a phone/email is supplied; a request that already carries a
 * {@code contactId} reuses it.
 */
@Slf4j
public class StylerMatchService {

    /** The widgetType a styler-match token must carry. */
    public static final String WIDGET_TYPE = "styler-match";

    private final PublicWidgetTokenService tokens;
    private final StylerMatchRepository matches;
    private final StaffMemberRepository staff;
    private final ServiceMenuRepository menus;
    private final BookingRepository bookings;
    private final ContactRepository contacts;
    private final StylerMatchScoringService scoringService;
    private final DomainEventPublisher events;

    public StylerMatchService(PublicWidgetTokenService tokens,
                              StylerMatchRepository matches,
                              StaffMemberRepository staff,
                              ServiceMenuRepository menus,
                              BookingRepository bookings,
                              ContactRepository contacts,
                              StylerMatchScoringService scoringService,
                              DomainEventPublisher events) {
        this.tokens = tokens;
        this.matches = matches;
        this.menus = menus;
        this.staff = staff;
        this.bookings = bookings;
        this.contacts = contacts;
        this.scoringService = scoringService;
        this.events = events;
    }

    /** The client's typed contact inputs from the intake form (all optional). */
    public record ManualContact(String name, String phone, String email, String notes) {
    }

    /**
     * Public-widget submit: verify the token, establish the synthetic tenant context, find-or-create the
     * client contact, score the stylists, persist the {@link StylerMatch}, and emit the advisory event.
     * Tenant is resolved from the token only. A wrong widgetType &rarr; {@code 4480}/401.
     */
    public Mono<StylerMatch> submitPublic(String token, MatchRequest request, ManualContact contact) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<StylerMatch>error(new DigiPresBeException(
                                "Styler-match token type mismatch (expected '" + WIDGET_TYPE
                                        + "', got '" + claims.widgetType() + "')", 4480, 401));
                    }
                    UUID tenantId = claims.tenantId();
                    TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
                    return runPipeline(tenantId, request, contact)
                            .contextWrite(TenantContextHolder.write(anon));
                });
    }

    /**
     * Staff-desk create: the caller is already in a {@code TenantContext}. Find-or-create the contact (if
     * a phone/email was supplied), score the stylists, persist, emit.
     */
    public Mono<StylerMatch> createForTenant(MatchRequest request, ManualContact contact) {
        return TenantContextHolder.required()
                .flatMap(ctx -> runPipeline(ctx.tenantId(), request, contact));
    }

    private Mono<StylerMatch> runPipeline(UUID tenantId, MatchRequest request, ManualContact contact) {
        MatchRequest req = request == null ? new MatchRequest() : request;
        if (req.isEmpty()) {
            return Mono.error(new DigiPresBeException(
                    "A styler match needs at least a service, a style, a slot, or a preferred stylist",
                    4481, 400));
        }
        ManualContact mc = contact == null ? new ManualContact(null, null, null, null) : contact;

        return resolveContactId(tenantId, req, mc).flatMap(contactIdOpt -> {
            UUID contactId = contactIdOpt.orElse(null);
            MatchRequest scored = req.toBuilder().contactId(contactId).build();
            return loadInputs(tenantId, scored)
                    .flatMap(inputs -> {
                        List<StaffMember> stylists = inputs.getT1();
                        if (stylists.isEmpty()) {
                            return Mono.error(new DigiPresBeException(
                                    "No active stylists to match", 4482, 404));
                        }
                        List<ServiceMenu> menuList = inputs.getT2();
                        List<Booking> bookingList = inputs.getT3();
                        List<RankedMatch> ranked = scoringService.rank(scored, stylists, menuList, bookingList);
                        String serviceName = serviceName(menuList, scored.getServiceMenuItemId());
                        double confidence = ranked.isEmpty() ? 0.0 : ranked.get(0).getConfidence();
                        return persist(tenantId, scored, mc, contactId, ranked, serviceName, confidence);
                    });
        });
    }

    /** Loads the three scoring inputs in parallel (stylists, menus, the tenant's bookings). */
    private Mono<Tuple3<List<StaffMember>, List<ServiceMenu>, List<Booking>>> loadInputs(
            UUID tenantId, MatchRequest req) {
        Mono<List<StaffMember>> stylists = staff.findByTenantIdAndActive(tenantId, true).collectList();
        Mono<List<ServiceMenu>> menuList = menus.findAllByTenantId(tenantId).collectList();
        // Bookings only matter when there is a slot (conflict) or a contact (preference); otherwise skip.
        Mono<List<Booking>> bookingList = (req.getSlotStart() != null || req.getContactId() != null)
                ? bookings.findAllByTenantId(tenantId).collectList()
                : Mono.just(List.of());
        return Mono.zip(stylists, menuList, bookingList)
                .map(t -> Tuples.of(t.getT1(), t.getT2(), t.getT3()));
    }

    private Mono<StylerMatch> persist(UUID tenantId, MatchRequest req, ManualContact mc, UUID contactId,
                                      List<RankedMatch> ranked, String serviceName, double confidence) {
        StylerMatch match = StylerMatch.builder()
                .tenantId(tenantId)
                .contactId(contactId)
                .contactPhone(trimToNull(mc.phone()))
                .contactEmail(trimToNull(mc.email()))
                .serviceMenuItemId(trimToNull(req.getServiceMenuItemId()))
                .serviceMenuItemName(serviceName)
                .styleCategory(trimToNull(req.getStyleCategory()))
                .length(trimToNull(req.getLength()))
                .texture(trimToNull(req.getTexture()))
                .color(trimToNull(req.getColor()))
                .preferredStaffMemberId(req.getPreferredStaffMemberId())
                .slotStart(req.getSlotStart())
                .slotEnd(req.getSlotEnd())
                .notes(trimToNull(mc.notes()))
                .rankedMatches(ranked)
                .confidence(confidence)
                .status(StylerMatchStatus.NEW)
                .build();
        return matches.save(match)
                .flatMap(saved -> Mono.fromRunnable(() -> emitRequested(tenantId, saved)).thenReturn(saved));
    }

    /**
     * Resolve the contact id (wrapped in an {@link Optional} so the anonymous case — empty — flows
     * through {@code flatMap}, which cannot carry null): a request that already carries {@code contactId}
     * keeps it; otherwise find-or-create by phone &rarr; email (explicit-boolean; never
     * {@code switchIfEmpty(create)} — the T9 {@code StyleConsultService.findOrCreateContact} precedent).
     * With no contactId and no phone/email the match is anonymous ({@code Optional.empty()} — no
     * preference signal, still ranked).
     */
    private Mono<Optional<UUID>> resolveContactId(UUID tenantId, MatchRequest req, ManualContact mc) {
        if (req.getContactId() != null) {
            return Mono.just(Optional.of(req.getContactId()));
        }
        String phone = trimToNull(mc.phone());
        String email = trimToNull(mc.email());
        if (phone == null && email == null) {
            return Mono.just(Optional.empty());
        }
        Mono<Contact> byPhone = phone == null ? Mono.empty()
                : contacts.findByTenantAndPhoneNumber(tenantId, phone).next();
        Mono<Contact> byEmail = email == null ? Mono.empty()
                : contacts.findByTenantAndEmailAddress(tenantId, email).next();
        return byPhone
                .switchIfEmpty(byEmail)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> existing.isPresent()
                        ? Mono.just(existing.get())
                        : contacts.save(buildContact(tenantId, mc)))
                .map(c -> Optional.of(c.getId()));
    }

    private Contact buildContact(UUID tenantId, ManualContact mc) {
        String phone = trimToNull(mc.phone());
        String email = trimToNull(mc.email());
        String name = trimToNull(mc.name());
        List<PhoneNumber> phones = new ArrayList<>();
        if (phone != null) {
            phones.add(PhoneNumber.builder().number(phone).label("styler-match").build());
        }
        List<EmailContact> emails = new ArrayList<>();
        if (email != null) {
            emails.add(new EmailContact(email));
        }
        String displayName = name != null ? name
                : (phone != null ? "Styler match " + phone
                : (email != null ? email : "Styler match"));
        return Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName(displayName)
                .phones(phones)
                .emails(emails)
                .tags(Set.of("stylermatch-lead"))
                .build();
    }

    private void emitRequested(UUID tenantId, StylerMatch match) {
        Map<String, Object> payload = new HashMap<>();
        if (match.getId() != null) payload.put("stylerMatchId", match.getId().toString());
        if (match.getContactId() != null) payload.put("contactId", match.getContactId().toString());
        if (match.getStyleCategory() != null) payload.put("styleCategory", match.getStyleCategory());
        if (match.getServiceMenuItemId() != null) {
            payload.put("serviceMenuItemId", match.getServiceMenuItemId());
        }
        payload.put("rankedCount", match.getRankedMatches() == null ? 0 : match.getRankedMatches().size());
        if (match.getRankedMatches() != null && !match.getRankedMatches().isEmpty()) {
            RankedMatch top = match.getRankedMatches().get(0);
            if (top.getStaffMemberId() != null) {
                payload.put("topStaffMemberId", top.getStaffMemberId().toString());
            }
            payload.put("topScore", top.getScore());
        }
        events.publish(DomainEvent.of(
                DomainEventType.STYLER_MATCH_REQUESTED, tenantId, match.getId(), payload));
    }

    private static String serviceName(List<ServiceMenu> menuList, String serviceMenuItemId) {
        if (menuList == null || serviceMenuItemId == null) {
            return null;
        }
        for (ServiceMenu m : menuList) {
            if (m == null || m.getServices() == null) continue;
            for (ServiceMenuItem item : m.getServices()) {
                if (item != null && serviceMenuItemId.equals(item.getId())) {
                    return item.getName();
                }
            }
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
