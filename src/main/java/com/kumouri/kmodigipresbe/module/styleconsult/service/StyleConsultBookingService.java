package com.kumouri.kmodigipresbe.module.styleconsult.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;
import com.kumouri.kmodigipresbe.module.styleconsult.repository.StyleConsultRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — S3: the consult → booking funnel. When a prospect accepts their
 * consult, this creates a real salon {@code Booking} for the chosen (or first-recommended) service via
 * the <strong>UNCHANGED</strong> {@link SalonBookingService#create}, links the consult → the booking,
 * and texts the prospect a booking link to pick a confirming time. The salon-flavored twin of the T8
 * {@code QuoteBookingService}.
 *
 * <h2>Reuse the salon booking path — no parallel booking logic, no live Cal.com (§7)</h2>
 * The booking is created through the existing {@code SalonBookingService.create} (which runs
 * {@code BookingPolicyService.validate} then lands CONFIRMED, since no deposit/time is set yet — a
 * general appointment the prospect schedules via the link). {@code SalonBookingService}/{@code Booking}
 * stay <strong>empty-diff</strong>. The booking link is the tenant's
 * {@code IntegrationConnection(twilio).config["bookingLink"]}, texted via the reused
 * {@link TwilioSmsService} — there is no live Cal.com call (the {@code QuoteBookingService} posture).
 *
 * <h2>Idempotent accept (explicit-boolean — never {@code switchIfEmpty(book)})</h2>
 * Load the consult tenant-scoped ({@code 4455} if absent). An <strong>explicit-boolean</strong> guard:
 * a consult already BOOKED ({@code bookingId != null}) re-confirms (creates no second {@code Booking},
 * sends nothing) and returns it. Otherwise resolve the service ({@code 4453} if the menu has nothing
 * bookable), create the {@code Booking}, stamp {@code bookingId}/{@code status=BOOKED}, text the link
 * (best-effort), and emit {@code STYLE_CONSULT_BOOKED}.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code StyleConsultAutoConfiguration} when the module is on.
 * Runs under the synthetic widget {@code TenantContext} the orchestrator establishes from the token.
 */
@Slf4j
public class StyleConsultBookingService {

    private static final String NOTIFY_PROVIDER = TwilioSmsService.PROVIDER; // "twilio"
    private static final String BOOKING_LINK_KEY = "bookingLink";

    private final StyleConsultRepository consults;
    private final SalonBookingService salonBookingService;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    public StyleConsultBookingService(StyleConsultRepository consults,
                                      SalonBookingService salonBookingService,
                                      IntegrationConnectionRepository connections,
                                      TwilioSmsService twilioSmsService,
                                      DomainEventPublisher events) {
        this.consults = consults;
        this.salonBookingService = salonBookingService;
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
    }

    /**
     * Accept the consult {@code consultId} for {@code tenantId}, creating a real {@code Booking} for the
     * chosen service and texting the booking link. {@code serviceMenuItemId} is optional — when null
     * (or not among the recommendations) the first recommended service is used. Idempotent: an
     * already-booked consult re-confirms with no second booking/SMS. {@code 4455} if the consult is not
     * found; {@code 4453} if no bookable service can be resolved.
     */
    public Mono<StyleConsult> accept(UUID tenantId, UUID consultId, String serviceMenuItemId) {
        return consults.findByTenantIdAndId(tenantId, consultId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Style consult not found", 4455, 404)))
                .flatMap(consult -> {
                    // Already booked → idempotent re-confirm, no second effect (explicit-boolean).
                    if (consult.getBookingId() != null
                            || consult.getStatus() == StyleConsultStatus.BOOKED) {
                        return Mono.just(consult);
                    }
                    ServiceRecommendation chosen = resolveService(consult, serviceMenuItemId);
                    if (chosen == null) {
                        return Mono.error(new DigiPresBeException(
                                "No bookable service to book for this consult", 4453, 404));
                    }
                    return resolveBookingLink(tenantId)
                            .flatMap(bookingLink -> createBooking(tenantId, consult, chosen)
                                    .flatMap(booking -> {
                                        consult.setBookingId(booking.getId());
                                        consult.setStatus(StyleConsultStatus.BOOKED);
                                        consult.setBookedAt(Instant.now());
                                        consult.setBookingLinkSent(bookingLink);
                                        return consults.save(consult)
                                                .flatMap(saved -> sendBookingSms(saved, bookingLink)
                                                        .then(Mono.fromRunnable(() ->
                                                                emitBooked(tenantId, saved)))
                                                        .thenReturn(saved));
                                    }));
                });
    }

    /** The chosen recommendation (by id) or the first recommended; null when there are none. */
    private static ServiceRecommendation resolveService(StyleConsult consult, String serviceMenuItemId) {
        List<ServiceRecommendation> recs = consult.getServiceRecommendations();
        if (recs == null || recs.isEmpty()) {
            return null;
        }
        if (serviceMenuItemId != null && !serviceMenuItemId.isBlank()) {
            for (ServiceRecommendation r : recs) {
                if (serviceMenuItemId.equals(r.getServiceMenuItemId())) {
                    return r;
                }
            }
        }
        return recs.get(0);
    }

    /**
     * Create the salon {@code Booking} via the unchanged {@link SalonBookingService#create}. No staff /
     * time is set (the prospect schedules via the booking link), so policy validation passes and the
     * booking lands CONFIRMED — a general appointment the salon firms up. Runs under the caller's
     * synthetic {@code TenantContext} ({@code SalonBookingService.create} reads it for the tenant id).
     */
    private Mono<Booking> createBooking(UUID tenantId, StyleConsult consult, ServiceRecommendation chosen) {
        Booking booking = Booking.builder()
                .contactId(consult.getContactId())
                .serviceMenuItemId(chosen.getServiceMenuItemId())
                .serviceMenuItemName(chosen.getName())
                .notes("From StyleConsult AI" + (consult.getId() != null
                        ? " (consult " + consult.getId() + ")" : ""))
                .build();
        return salonBookingService.create(booking);
    }

    /** The tenant's configured booking link (null/empty when unset — the SMS then just confirms). */
    private Mono<String> resolveBookingLink(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, NOTIFY_PROVIDER)
                .map(conn -> {
                    Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
                    return config.getOrDefault(BOOKING_LINK_KEY, "");
                })
                .defaultIfEmpty("");
    }

    /**
     * Best-effort booking-link SMS to the prospect (a send failure never fails the accept — the booking
     * is already durable). Skipped when no contact phone is on the consult.
     */
    private Mono<Void> sendBookingSms(StyleConsult consult, String bookingLink) {
        String to = consult.getContactPhone();
        if (to == null || to.isBlank()) {
            return Mono.empty();
        }
        String body = (bookingLink != null && !bookingLink.isBlank())
                ? "Love it — let's get you in the chair! Book your appointment here: " + bookingLink
                : "Love it — let's get you in the chair! We'll reach out shortly to schedule your appointment.";
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(PhoneContact.builder().e164(to).build())
                .body(body)
                .build();
        return twilioSmsService.sendSms(req)
                .doOnError(e -> log.warn("StyleConsult booking-link SMS to {} failed (best-effort, "
                        + "ignored): {}", to, e.getMessage()))
                .onErrorReturn(false)
                .then();
    }

    private void emitBooked(UUID tenantId, StyleConsult consult) {
        Map<String, Object> payload = new HashMap<>();
        if (consult.getId() != null) payload.put("styleConsultId", consult.getId().toString());
        if (consult.getContactId() != null) payload.put("contactId", consult.getContactId().toString());
        if (consult.getBookingId() != null) payload.put("bookingId", consult.getBookingId().toString());
        payload.put("bookingLinkSent", consult.getBookingLinkSent() != null
                && !consult.getBookingLinkSent().isBlank());
        events.publish(DomainEvent.of(
                DomainEventType.STYLE_CONSULT_BOOKED, tenantId, consult.getId(), payload));
    }
}
