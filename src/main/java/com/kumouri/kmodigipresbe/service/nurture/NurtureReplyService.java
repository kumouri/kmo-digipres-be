package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles a positive reply to a nurture cadence: exit the enrollment and (best-effort) send the
 * tenant's Cal.com booking link over SMS (E1 — Nurture / Cadence Engine).
 *
 * <h2>Reply → exit → book</h2>
 * Resolves the target enrollment (by id, or — for the inbound-SMS path landing in E2 — the newest
 * non-terminal enrollment for the contact matched on phone), then under the tenant context:
 * <ol>
 *   <li>Set {@code status=REPLIED, repliedAt=now}, save → emit {@code NURTURE_POSITIVE_REPLY}.</li>
 *   <li>Read the per-tenant booking link from {@code IntegrationConnection(twilio).config["bookingLink"]}
 *       (NOT hardcoded — the {@code LeadHandoffService} config-read precedent). If present, SMS the
 *       "book a time" message to the contact's phone (best-effort {@code onErrorResume}), then set
 *       {@code status=BOOKED}, save → emit {@code NURTURE_BOOKING_LINK_SENT}. <strong>No live Cal.com
 *       API call</strong> (the flagship booking-link-SMS pattern). A missing link leaves the enrollment
 *       {@code REPLIED} (the reply exit still happened; the link is a best-effort add-on).</li>
 * </ol>
 *
 * <h2>§9</h2>
 * {@code switchIfEmpty} is used only for the genuine enrollment-not-found (4310). An already-terminal
 * enrollment is an explicit-boolean guard → 4311 (a second reply is a no-op error, never a re-book).
 */
@Slf4j
public class NurtureReplyService {

    /** The per-tenant Cal.com booking-link key on {@code IntegrationConnection(twilio).config}. */
    public static final String BOOKING_LINK_KEY = "bookingLink";

    private final NurtureEnrollmentRepository enrollments;
    private final ContactRepository contacts;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final String bookingMessageTemplate;

    public NurtureReplyService(NurtureEnrollmentRepository enrollments,
                               ContactRepository contacts,
                               IntegrationConnectionRepository connections,
                               TwilioSmsService twilioSmsService,
                               DomainEventPublisher events,
                               ObjectProvider<Clock> clockProvider,
                               @Value("${kmosf.modules.nurture.booking-message:"
                                       + "Great — let's find a time. Book here: {link}}")
                               String bookingMessageTemplate) {
        this.enrollments = enrollments;
        this.contacts = contacts;
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.bookingMessageTemplate = bookingMessageTemplate;
    }

    /**
     * Handle a positive reply by enrollment id.
     *
     * @param tenantId     the tenant
     * @param enrollmentId the enrollment to exit + book
     * @return the updated enrollment (BOOKED if a booking link was sent, else REPLIED)
     */
    public Mono<NurtureEnrollment> handlePositiveReplyByEnrollment(UUID tenantId, UUID enrollmentId) {
        return enrollments.findByTenantIdAndId(tenantId, enrollmentId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Nurture enrollment not found", 4310, 404)))
                .flatMap(enr -> processReply(tenantId, enr));
    }

    /**
     * Handle a positive reply by the contact's phone number (the E2 inbound-SMS entry). Resolves the
     * newest non-terminal enrollment for any contact matching the phone. No such enrollment → 4310.
     *
     * @param tenantId    the tenant
     * @param contactPhone the sender's phone (E.164)
     * @return the updated enrollment
     */
    public Mono<NurtureEnrollment> handlePositiveReplyByPhone(UUID tenantId, String contactPhone) {
        if (contactPhone == null || contactPhone.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "A contact phone is required to resolve the enrollment", 4312, 400));
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, contactPhone)
                .concatMap(contact -> enrollments
                        .findAllByTenantIdAndContactIdAndStatusInOrderByEnrolledAtDesc(
                                tenantId, contact.getId(),
                                List.of(NurtureEnrollmentStatus.ENROLLED, NurtureEnrollmentStatus.ACTIVE)))
                .next()
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "No active nurture enrollment found for that contact phone", 4310, 404)))
                .flatMap(enr -> processReply(tenantId, enr));
    }

    private Mono<NurtureEnrollment> processReply(UUID tenantId, NurtureEnrollment enr) {
        if (enr.getStatus() != null && enr.getStatus().isTerminal()) {
            return Mono.error(new DigiPresBeException(
                    "Nurture enrollment is already terminal (" + enr.getStatus() + ")", 4311, 409));
        }
        NurtureEnrollment replied = enr.toBuilder()
                .status(NurtureEnrollmentStatus.REPLIED)
                .repliedAt(clock.instant())
                .nextFireAt(null)
                .build();
        return enrollments.save(replied)
                .doOnNext(saved -> events.publish(DomainEvent.of(
                        DomainEventType.NURTURE_POSITIVE_REPLY, tenantId, saved.getId(),
                        Map.of("campaignId", saved.getCampaignId().toString(),
                                "contactId", saved.getContactId().toString()))))
                .flatMap(saved -> sendBookingLinkAndMarkBooked(tenantId, saved));
    }

    /**
     * Best-effort booking-link SMS → BOOKED. Missing link or no phone leaves the enrollment REPLIED
     * (the exit already happened). A send failure is swallowed but the enrollment still advances to
     * BOOKED (the link attempt was made; Rob has the reply either way). No live Cal.com call.
     */
    private Mono<NurtureEnrollment> sendBookingLinkAndMarkBooked(UUID tenantId, NurtureEnrollment replied) {
        return connections.findByTenantIdAndProvider(tenantId, TwilioSmsService.PROVIDER)
                .flatMap(conn -> {
                    String link = bookingLinkOf(conn);
                    if (link == null) {
                        log.info("Nurture positive-reply: no bookingLink on tenant {} Twilio config — "
                                + "enrollment {} stays REPLIED", tenantId, replied.getId());
                        return Mono.just(replied);
                    }
                    return contacts.findByTenantIdAndId(tenantId, replied.getContactId())
                            .flatMap(contact -> sendBookingSms(contact, link))
                            .then(markBooked(tenantId, replied));
                })
                // No Twilio connection at all → can't send a link; the reply exit still stands.
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Nurture positive-reply: tenant {} has no Twilio connection — enrollment "
                            + "{} stays REPLIED", tenantId, replied.getId());
                    return Mono.just(replied);
                }));
    }

    private Mono<NurtureEnrollment> markBooked(UUID tenantId, NurtureEnrollment replied) {
        return enrollments.save(replied.toBuilder()
                        .status(NurtureEnrollmentStatus.BOOKED)
                        .build())
                .doOnNext(saved -> events.publish(DomainEvent.of(
                        DomainEventType.NURTURE_BOOKING_LINK_SENT, tenantId, saved.getId(),
                        Map.of("campaignId", saved.getCampaignId().toString(),
                                "contactId", saved.getContactId().toString()))));
    }

    private Mono<Void> sendBookingSms(Contact contact, String link) {
        String phone = firstPhone(contact);
        if (phone == null) {
            log.info("Nurture positive-reply: contact {} has no phone — skipping booking SMS",
                    contact.getId());
            return Mono.empty();
        }
        String body = bookingMessageTemplate.replace("{link}", link);
        return twilioSmsService.sendSms(SmsCommunicationRequest.builder()
                        .to(new PhoneContact(phone))
                        .body(body)
                        .build())
                .onErrorResume(e -> {
                    log.warn("Nurture booking-link SMS failed (best-effort, ignored): {}", e.toString());
                    return Mono.just(false);
                })
                .then();
    }

    private static String bookingLinkOf(IntegrationConnection conn) {
        Map<String, String> config = conn.getConfig();
        if (config == null) {
            return null;
        }
        String link = config.get(BOOKING_LINK_KEY);
        return (link == null || link.isBlank()) ? null : link;
    }

    private static String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null) {
            return null;
        }
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number();
            }
        }
        return null;
    }
}
