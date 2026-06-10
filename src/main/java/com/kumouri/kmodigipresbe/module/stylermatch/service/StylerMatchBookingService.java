package com.kumouri.kmodigipresbe.module.stylermatch.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;
import com.kumouri.kmodigipresbe.module.stylermatch.repository.StylerMatchRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — P3: the match &rarr; booking funnel. When a client accepts a ranked
 * stylist, this creates a real salon {@code Booking} for that stylist + the requested service via the
 * <strong>UNCHANGED</strong> {@link SalonBookingService#create}, links the match &rarr; the booking,
 * stamps which rank was booked (the analytics signal), and texts the client a booking link to confirm a
 * time. The stylist-side twin of the T9 {@code StyleConsultBookingService}.
 *
 * <h2>Reuse the salon booking path — the hard gate still bites</h2>
 * The booking is created through {@code SalonBookingService.create}, which runs
 * {@code BookingPolicyService.validate} — so the HARD eligibility ({@code StaffMember.eligibleServiceIds},
 * 2900) + availability ({@code 2901}) constraints are enforced at booking time. A stylist who ranked
 * <em>visible-but-penalized</em> because they are not certified for the requested service is correctly
 * <strong>rejected here</strong> if the client nonetheless picks them — the ranker shows the whole board,
 * the booking step is the authority. {@code SalonBookingService}/{@code Booking} stay
 * <strong>empty-diff</strong>. When a {@code slotStart}/{@code slotEnd} was requested it is carried onto
 * the booking; otherwise the booking lands CONFIRMED as a general appointment the client schedules via
 * the link. The booking link is the tenant's {@code IntegrationConnection(twilio).config["bookingLink"]},
 * texted via the reused {@link TwilioSmsService} — no live Cal.com (the T9 posture).
 *
 * <h2>Idempotent accept (explicit-boolean — never {@code switchIfEmpty(book)})</h2>
 * Load the match tenant-scoped ({@code 4485} if absent). An <strong>explicit-boolean</strong> guard: a
 * match already BOOKED ({@code bookingId != null}) re-confirms (creates no second {@code Booking}, sends
 * nothing) and returns it. Otherwise resolve the chosen stylist (the {@code staffMemberId} param, else
 * the top-ranked; {@code 4483} if the board is empty), create the {@code Booking}, stamp
 * {@code selectedStaffMemberId}/{@code selectedRank}/{@code bookingId}/{@code status=BOOKED}, text the
 * link (best-effort), and emit {@code STYLER_MATCH_BOOKED}.
 */
@Slf4j
public class StylerMatchBookingService {

    private static final String NOTIFY_PROVIDER = TwilioSmsService.PROVIDER; // "twilio"
    private static final String BOOKING_LINK_KEY = "bookingLink";

    private final StylerMatchRepository matches;
    private final SalonBookingService salonBookingService;
    private final ServiceMenuRepository menus;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    public StylerMatchBookingService(StylerMatchRepository matches,
                                     SalonBookingService salonBookingService,
                                     ServiceMenuRepository menus,
                                     IntegrationConnectionRepository connections,
                                     TwilioSmsService twilioSmsService,
                                     DomainEventPublisher events) {
        this.matches = matches;
        this.salonBookingService = salonBookingService;
        this.menus = menus;
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
    }

    /**
     * Accept the match {@code matchId} for {@code tenantId}, booking the chosen stylist + the requested
     * service and texting the booking link. {@code staffMemberId} is optional — when null (or not among
     * the ranked matches) the top-ranked stylist is used. Idempotent: an already-booked match re-confirms
     * with no second booking/SMS. {@code 4485} if the match is not found; {@code 4483} if no rankable
     * stylist can be resolved.
     */
    public Mono<StylerMatch> accept(UUID tenantId, UUID matchId, UUID staffMemberId) {
        return matches.findByTenantIdAndId(tenantId, matchId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Styler match not found", 4485, 404)))
                .flatMap(match -> {
                    // Already booked → idempotent re-confirm, no second effect (explicit-boolean).
                    if (match.getBookingId() != null || match.getStatus() == StylerMatchStatus.BOOKED) {
                        return Mono.just(match);
                    }
                    int rank = resolveRank(match, staffMemberId);
                    if (rank < 0) {
                        return Mono.error(new DigiPresBeException(
                                "No rankable stylist to book for this match", 4483, 404));
                    }
                    RankedMatch chosen = match.getRankedMatches().get(rank);
                    return resolveBookingLink(tenantId)
                            .flatMap(bookingLink -> resolveServiceName(tenantId, match)
                                    .flatMap(serviceName -> createBooking(match, chosen, serviceName)
                                            .flatMap(booking -> {
                                                match.setSelectedStaffMemberId(chosen.getStaffMemberId());
                                                match.setSelectedRank(rank + 1); // 1-based
                                                match.setBookingId(booking.getId());
                                                match.setStatus(StylerMatchStatus.BOOKED);
                                                match.setBookedAt(Instant.now());
                                                match.setBookingLinkSent(bookingLink);
                                                return matches.save(match)
                                                        .flatMap(saved -> sendBookingSms(saved, bookingLink)
                                                                .then(Mono.fromRunnable(() ->
                                                                        emitBooked(tenantId, saved)))
                                                                .thenReturn(saved));
                                            })));
                });
    }

    /**
     * The index in {@code rankedMatches} of the chosen stylist: the {@code staffMemberId} param if it
     * matches a ranked entry, else the top rank (0). Returns -1 when the board is empty.
     */
    private static int resolveRank(StylerMatch match, UUID staffMemberId) {
        List<RankedMatch> ranked = match.getRankedMatches();
        if (ranked == null || ranked.isEmpty()) {
            return -1;
        }
        if (staffMemberId != null) {
            for (int i = 0; i < ranked.size(); i++) {
                if (staffMemberId.equals(ranked.get(i).getStaffMemberId())) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * Create the salon {@code Booking} via the unchanged {@link SalonBookingService#create}. The chosen
     * stylist + the requested service (+ the requested slot, when one was given) are set, so
     * {@code BookingPolicyService} enforces the hard eligibility + availability; the booking lands
     * CONFIRMED (no deposit) or PENDING_DEPOSIT per the salon policy. Runs under the caller's
     * {@code TenantContext} ({@code SalonBookingService.create} reads it for the tenant id).
     */
    private Mono<Booking> createBooking(StylerMatch match, RankedMatch chosen, String serviceName) {
        Booking booking = Booking.builder()
                .contactId(match.getContactId())
                .staffMemberId(chosen.getStaffMemberId())
                .serviceMenuItemId(match.getServiceMenuItemId())
                .serviceMenuItemName(serviceName)
                .scheduledStart(match.getSlotStart())
                .scheduledEnd(match.getSlotEnd())
                .notes("From StylerMatch" + (match.getId() != null
                        ? " (match " + match.getId() + ")" : ""))
                .build();
        return salonBookingService.create(booking);
    }

    /** The requested service's name — the match snapshot, or a fresh menu read fallback. */
    private Mono<String> resolveServiceName(UUID tenantId, StylerMatch match) {
        if (match.getServiceMenuItemName() != null && !match.getServiceMenuItemName().isBlank()) {
            return Mono.just(match.getServiceMenuItemName());
        }
        if (match.getServiceMenuItemId() == null) {
            return Mono.just("");
        }
        return menus.findAllByTenantId(tenantId).collectList()
                .map(menuList -> {
                    String name = serviceName(menuList, match.getServiceMenuItemId());
                    return name == null ? "" : name;
                });
    }

    /** The tenant's configured booking link (empty when unset — the SMS then just confirms). */
    private Mono<String> resolveBookingLink(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, NOTIFY_PROVIDER)
                .map(conn -> {
                    Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
                    return config.getOrDefault(BOOKING_LINK_KEY, "");
                })
                .defaultIfEmpty("");
    }

    /**
     * Best-effort booking-link SMS to the client (a send failure never fails the accept — the booking is
     * already durable). Skipped when no contact phone is on the match.
     */
    private Mono<Void> sendBookingSms(StylerMatch match, String bookingLink) {
        String to = match.getContactPhone();
        if (to == null || to.isBlank()) {
            return Mono.empty();
        }
        String body = (bookingLink != null && !bookingLink.isBlank())
                ? "Great choice — let's get you in the chair! Confirm your appointment here: " + bookingLink
                : "Great choice — let's get you in the chair! We'll reach out shortly to confirm your time.";
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(PhoneContact.builder().e164(to).build())
                .body(body)
                .build();
        return twilioSmsService.sendSms(req)
                .doOnError(e -> log.warn("StylerMatch booking-link SMS to {} failed (best-effort, "
                        + "ignored): {}", to, e.getMessage()))
                .onErrorReturn(false)
                .then();
    }

    private void emitBooked(UUID tenantId, StylerMatch match) {
        Map<String, Object> payload = new HashMap<>();
        if (match.getId() != null) payload.put("stylerMatchId", match.getId().toString());
        if (match.getContactId() != null) payload.put("contactId", match.getContactId().toString());
        if (match.getSelectedStaffMemberId() != null) {
            payload.put("staffMemberId", match.getSelectedStaffMemberId().toString());
        }
        if (match.getBookingId() != null) payload.put("bookingId", match.getBookingId().toString());
        if (match.getSelectedRank() != null) payload.put("selectedRank", match.getSelectedRank());
        events.publish(DomainEvent.of(
                DomainEventType.STYLER_MATCH_BOOKED, tenantId, match.getId(), payload));
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
}
