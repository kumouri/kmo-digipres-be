package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.OfferedShowingSlot;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.MeetingRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-3) — books a showing over SMS (RE-3 §5 / decision 4): when the buyer wants to
 * see the listing, the concierge <strong>offers candidate showing slots</strong>, the buyer picks one (a
 * number/slot reply), and a {@code Meeting} is written.
 *
 * <h2>Two booking turns through the existing router</h2>
 * The flow spans two inbound-SMS turns the {@link ConciergeInboundRouter} routes here:
 * <ol>
 *   <li><strong>Offer.</strong> A buyer turn carrying showing intent ({@link #hasShowingIntent}) →
 *       {@link #offerSlots}: generate a small set of demo-grade candidate slots from the listing/agent
 *       availability config, persist them on the conversation, advance the state to
 *       {@link ConversationState#OFFERING_SLOTS}, and text the offer ("I've got Sat 2:00 PM or Sat 4:00 PM
 *       — reply 1 or 2").</li>
 *   <li><strong>Book.</strong> The next buyer turn, while the conversation is in {@code OFFERING_SLOTS}, is
 *       routed (by the router's state check) to {@link #book}: parse the pick from the persisted
 *       {@code offeredSlots}, <strong>write a {@code Meeting}</strong> for the chosen time, link it onto the
 *       conversation, advance the state to {@link ConversationState#BOOKED}, log a best-effort
 *       {@code Activity(MEETING)} on the buyer contact, emit {@code SHOWING_BOOKED}, and text the
 *       confirmation ("Booked! Sat 2:00 PM. Your agent will meet you there.").</li>
 * </ol>
 *
 * <h2>Demo writes the Meeting projection directly (no live Cal.com — §7)</h2>
 * The {@code Meeting} is written with the {@code CalComWebhookService.reconcileUpsert} projection shape —
 * tenant-scoped, the listing's address as {@code location}, the buyer as an attendee, the agent as the
 * organizer, the chosen {@code start}/{@code end}. It is written DIRECTLY (no live Cal.com booking call).
 * Because {@code calComBookingUid} is left null, a later production Cal.com booking + the shipped webhook
 * reconcile (keyed on {@code calComBookingUid}) creates its own projection cleanly — the production path
 * flips on with no concierge change.
 *
 * <h2>Best-effort &amp; idempotent</h2>
 * Everything is best-effort (RE-3 HARD GATE 2): an SMS or Meeting-write failure never drops the
 * conversation (advisory codes 4263/4264, logged, never thrown). A double-pick race is guarded by an
 * explicit-boolean check on the conversation's {@code meetingId} (already booked → re-confirm the existing
 * Meeting, never a second one) backed by the conversation {@code @Version} optimistic lock. Runs under the
 * caller's synthetic {@code TenantContext}. Hand-constructed as a {@code @Bean} when the module is enabled.
 */
@Slf4j
public class ShowingBookingService {

    private final ConciergeConversationRepository conversations;
    private final MeetingRepository meetings;
    private final ContactRepository contacts;
    private final ActivityCrudService activityCrudService;
    private final com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final Clock clock;

    /** How many candidate slots to offer. */
    private final int slotCount;
    /** The minutes a showing block lasts. */
    private final int slotDurationMinutes;
    /** The hours-of-day (24h) candidate showings start at, in offer order. */
    private final List<Integer> slotHours;

    public ShowingBookingService(ConciergeConversationRepository conversations,
                                 MeetingRepository meetings,
                                 ContactRepository contacts,
                                 ActivityCrudService activityCrudService,
                                 com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService twilioSmsService,
                                 DomainEventPublisher events,
                                 int slotCount,
                                 int slotDurationMinutes,
                                 List<Integer> slotHours) {
        this(conversations, meetings, contacts, activityCrudService, twilioSmsService, events,
                Clock.systemDefaultZone(), slotCount, slotDurationMinutes, slotHours);
    }

    /** Package/visible-for-test constructor that injects a fixed {@link Clock} for deterministic slots. */
    ShowingBookingService(ConciergeConversationRepository conversations,
                          MeetingRepository meetings,
                          ContactRepository contacts,
                          ActivityCrudService activityCrudService,
                          com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService twilioSmsService,
                          DomainEventPublisher events,
                          Clock clock,
                          int slotCount,
                          int slotDurationMinutes,
                          List<Integer> slotHours) {
        this.conversations = conversations;
        this.meetings = meetings;
        this.contacts = contacts;
        this.activityCrudService = activityCrudService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.clock = clock;
        this.slotCount = Math.max(1, slotCount);
        this.slotDurationMinutes = Math.max(15, slotDurationMinutes);
        this.slotHours = (slotHours == null || slotHours.isEmpty()) ? List.of(10, 14, 16) : slotHours;
    }

    // ── intent detection (the cheap pre-filter — no model call) ──────────────────

    /**
     * Cheap, deterministic heuristic: does this buyer text plausibly express intent to see/visit the
     * listing? Mirrors {@code ConciergeInboundRouter.hasQualificationSignal} — keeps a pure factual
     * disclosure question (no showing language) from ever entering the booking flow, so the RE-1 grounded
     * path's model-call behavior stays byte-identical (the {@code RealEstateConciergeIT} call-count gate).
     */
    public static boolean hasShowingIntent(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String s = body.toLowerCase();
        return s.contains("see it") || s.contains("see the") || s.contains("view it")
                || s.contains("view the") || s.contains("tour") || s.contains("showing")
                || s.contains("show it") || s.contains("walk through") || s.contains("walkthrough")
                || s.contains("come by") || s.contains("come look") || s.contains("come see")
                || s.contains("stop by") || s.contains("visit") || s.contains("open house")
                || s.contains("look at it") || s.contains("look at the")
                || s.contains("check it out") || s.contains("schedule a") || s.contains("book a");
    }

    /**
     * Parses a slot pick out of a buyer reply against the persisted offered slots. Accepts a bare number
     * ("2"), a number embedded in a short reply ("the 2nd one", "option 1", "2pm works"), or a slot-label
     * substring match ("Saturday 2", "sat 2pm"). Returns the chosen {@link OfferedShowingSlot}, or empty
     * when nothing matched (the buyer is re-offered).
     */
    static Optional<OfferedShowingSlot> resolvePick(String body, List<OfferedShowingSlot> offered) {
        if (body == null || offered == null || offered.isEmpty()) {
            return Optional.empty();
        }
        String s = body.trim().toLowerCase();

        // 1) An exact bare ordinal ("2") — the happy path the offer SMS instructs ("reply 1 or 2").
        for (OfferedShowingSlot slot : offered) {
            if (s.equals(Integer.toString(slot.getOrdinal()))) {
                return Optional.of(slot);
            }
        }
        // 2) An ordinal token surrounded by word boundaries ("option 2", "the 2nd", "2 works").
        for (OfferedShowingSlot slot : offered) {
            if (s.matches(".*\\b" + slot.getOrdinal() + "\\b.*")) {
                return Optional.of(slot);
            }
        }
        // 3) A label substring match ("sat 2:00", "saturday 2pm") — tolerant of the label's phrasing.
        for (OfferedShowingSlot slot : offered) {
            String label = slot.getLabel() == null ? "" : slot.getLabel().toLowerCase();
            if (!label.isBlank() && s.contains(label)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    // ── (1) offer slots ──────────────────────────────────────────────────────────

    /**
     * Offers candidate showing slots to the buyer for {@code listing}: generates the slots, persists them
     * on {@code conv}, advances the state to {@link ConversationState#OFFERING_SLOTS}, and texts the offer.
     * Best-effort: a generation/SMS failure logs 4263 and leaves the conversation unchanged (the caller's
     * answer/handoff turn already happened) — never throws. Returns the saved conversation.
     */
    public Mono<ConciergeConversation> offerSlots(UUID tenantId, Listing listing,
                                                  ConciergeConversation conv, String buyerPhone) {
        List<OfferedShowingSlot> slots = generateSlots();
        if (slots.isEmpty()) {
            // No availability to offer (4263) — fall back to a graceful note; never throws.
            log.info("RE-3 showing: no availability to offer for listing {} (4263)", listing.getId());
            return reply(buyerPhone, "I'd love to set up a showing — your agent will reach out with times "
                    + "shortly.").thenReturn(conv);
        }
        conv.setOfferedSlots(slots);
        conv.setState(ConversationState.OFFERING_SLOTS);
        return conversations.save(conv)
                .flatMap(saved -> reply(buyerPhone, buildOfferSms(slots)).thenReturn(saved))
                .onErrorResume(err -> {
                    log.warn("RE-3 showing: slot offer failed (best-effort, 4263) for conversation {}: {}",
                            conv.getId(), err.toString());
                    return Mono.just(conv);
                });
    }

    // ── (2) book the picked slot ───────────────────────────────────────────────────

    /**
     * Books the slot the buyer picked (while the conversation is in {@code OFFERING_SLOTS}): resolves the
     * pick from the persisted {@code offeredSlots}, writes a {@code Meeting} for the chosen time, links it
     * onto the conversation, advances to {@link ConversationState#BOOKED}, logs a best-effort
     * {@code Activity(MEETING)}, emits {@code SHOWING_BOOKED}, and texts the confirmation. If the pick
     * doesn't match an offered slot, re-offers (state stays {@code OFFERING_SLOTS}). Idempotent: a
     * conversation that already booked re-confirms the existing Meeting (no second write — 4264). Best-effort
     * throughout — never throws. Returns the saved conversation.
     */
    public Mono<ConciergeConversation> book(UUID tenantId, Listing listing,
                                            ConciergeConversation conv, String buyerPhone, String body) {
        // Idempotency / double-pick guard: already booked → re-confirm, never a second Meeting (4264).
        if (conv.getMeetingId() != null) {
            log.debug("RE-3 showing: conversation {} already booked meeting {} — re-confirm (4264)",
                    conv.getId(), conv.getMeetingId());
            return reply(buyerPhone, "You're all set — your showing is already booked. Your agent will meet "
                    + "you there.").thenReturn(conv);
        }

        Optional<OfferedShowingSlot> picked = resolvePick(body, conv.getOfferedSlots());
        if (picked.isEmpty()) {
            // Couldn't read the pick — re-offer the same slots (state stays OFFERING_SLOTS).
            log.debug("RE-3 showing: conversation {} pick '{}' did not match an offered slot — re-offer",
                    conv.getId(), body);
            List<OfferedShowingSlot> slots = conv.getOfferedSlots();
            String reoffer = (slots == null || slots.isEmpty())
                    ? "Sorry, I didn't catch that — your agent will follow up with showing times."
                    : "Sorry, I didn't catch that. " + buildOfferSms(slots);
            return reply(buyerPhone, reoffer).thenReturn(conv);
        }

        OfferedShowingSlot slot = picked.get();
        return resolveBuyerContact(tenantId, conv)
                .flatMap(contact -> writeMeeting(tenantId, listing, contact, slot)
                        .flatMap(meeting -> {
                            conv.setMeetingId(meeting.getId());
                            conv.setState(ConversationState.BOOKED);
                            conv.setOfferedSlots(new ArrayList<>());
                            if (conv.getContactId() == null) {
                                conv.setContactId(contact.getId());
                            }
                            return conversations.save(conv)
                                    .flatMap(savedConv -> logActivity(tenantId, contact, listing, meeting, slot)
                                            .then(reply(buyerPhone, buildConfirmationSms(slot)))
                                            .doOnSuccess(v -> emitShowingBooked(tenantId, savedConv, listing,
                                                    meeting, contact, slot))
                                            .thenReturn(savedConv));
                        }))
                .onErrorResume(err -> {
                    log.warn("RE-3 showing: booking failed (best-effort, 4264) for conversation {}: {}",
                            conv.getId(), err.toString());
                    return Mono.just(conv);
                });
    }

    // ── Meeting projection write (the CalComWebhookService.reconcileUpsert shape) ──

    /**
     * Writes the showing {@code Meeting} directly (the demo path — no live Cal.com). Mirrors
     * {@code CalComWebhookService.reconcileUpsert}'s created-projection shape: tenant-scoped, the chosen
     * {@code start}/{@code end}, the listing address as {@code location}, the buyer as the sole attendee, the
     * listing agent (when known) as the organizer. {@code calComBookingUid} is intentionally left null so a
     * later production Cal.com booking reconciles to its own projection (no collision).
     */
    private Mono<Meeting> writeMeeting(UUID tenantId, Listing listing, Contact contact,
                                       OfferedShowingSlot slot) {
        String address = listing != null && listing.getAddressLine() != null
                ? listing.getAddressLine() : "the property";
        Meeting meeting = Meeting.builder()
                .tenantId(tenantId)
                .name("Showing — " + address)
                .description("Property showing booked over SMS by the listing concierge.")
                .location(address)
                .start(slot.getStart())
                .end(slot.getEnd())
                .allDay(false)
                .organizerContactId(listing != null ? listing.getAgentContactId() : null)
                .attendeeContactIds(contact != null && contact.getId() != null
                        ? Set.of(contact.getId()) : Set.of())
                .build();
        return meetings.save(meeting);
    }

    /**
     * Best-effort {@code Activity(MEETING, subjectType=CONTACT)} on the buyer contact (the
     * {@code CalComWebhookService} pattern). A telemetry failure never fails the booking.
     */
    private Mono<Void> logActivity(UUID tenantId, Contact contact, Listing listing, Meeting meeting,
                                   OfferedShowingSlot slot) {
        if (contact == null || contact.getId() == null) {
            return Mono.empty();
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("meetingId", meeting.getId().toString());
        if (listing != null && listing.getId() != null) {
            payload.put("listingId", listing.getId().toString());
        }
        payload.put("start", slot.getStart() == null ? "" : slot.getStart().toString());
        Activity activity = Activity.builder()
                .tenantId(tenantId)
                .type(ActivityType.MEETING)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contact.getId())
                .summary("Showing booked: " + (slot.getLabel() != null ? slot.getLabel() : "showing"))
                .payload(payload)
                .build();
        return activityCrudService.create(activity)
                .doOnError(e -> log.warn("RE-3 showing: Activity(MEETING) creation failed (best-effort, "
                        + "ignored): {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * Resolve the buyer Contact for the booking: reuse the conversation's linked contact (RE-2 may have
     * materialized it during qualification); else find-or-create by {@code buyerPhone} (the
     * {@code QualificationService} / {@code TwilioVoicemailService} precedent — an existing contact is reused
     * untouched). The Meeting's attendee links to a real Contact so the showing appears on their timeline.
     */
    private Mono<Contact> resolveBuyerContact(UUID tenantId, ConciergeConversation conv) {
        if (conv.getContactId() != null) {
            return contacts.findByTenantIdAndId(tenantId, conv.getContactId())
                    .switchIfEmpty(Mono.defer(() ->
                            contacts.save(buildContact(tenantId, conv.getBuyerPhone()))));
        }
        String phone = conv.getBuyerPhone();
        if (phone == null || phone.isBlank()) {
            return contacts.save(buildContact(tenantId, null));
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, phone)
                .next()
                .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(tenantId, phone))));
    }

    private Contact buildContact(UUID tenantId, String phone) {
        List<PhoneNumber> phones = new ArrayList<>();
        if (phone != null && !phone.isBlank()) {
            phones.add(PhoneNumber.builder().number(phone).label("concierge").build());
        }
        String displayName = phone != null ? "Listing buyer " + phone : "Listing buyer";
        return Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName(displayName)
                .phones(phones)
                .tags(Set.of("realestate-buyer"))
                .build();
    }

    // ── slot generation (demo-grade; production = Cal.com availability) ───────────

    /**
     * Generates the next {@link #slotCount} candidate showing slots from the configured {@link #slotHours},
     * starting tomorrow (so a slot is never in the past), skipping any hour already elapsed today is moot
     * since we start from the next day. <strong>Demo-grade</strong>: a deterministic local availability
     * generator. The production path reads the agent's live Cal.com availability (§7 / decision 4) — a clean
     * swap of this method.
     */
    private List<OfferedShowingSlot> generateSlots() {
        List<OfferedShowingSlot> slots = new ArrayList<>();
        LocalDate day = LocalDate.now(clock).plusDays(1);
        int ordinal = 1;
        // Walk forward day-by-day, emitting the configured hours, until we have slotCount slots.
        while (slots.size() < slotCount) {
            for (Integer hour : slotHours) {
                if (slots.size() >= slotCount) {
                    break;
                }
                LocalDateTime start = LocalDateTime.of(day, LocalTime.of(hour, 0));
                LocalDateTime end = start.plusMinutes(slotDurationMinutes);
                slots.add(OfferedShowingSlot.builder()
                        .ordinal(ordinal++)
                        .label(formatSlotLabel(start))
                        .start(start)
                        .end(end)
                        .build());
            }
            day = day.plusDays(1);
        }
        return slots;
    }

    /** "Sat 2:00 PM" — the human phrase used in both the offer + the confirmation SMS. */
    private static String formatSlotLabel(LocalDateTime start) {
        DayOfWeek dow = start.getDayOfWeek();
        String dayName = dow.getDisplayName(TextStyle.SHORT, Locale.US);
        int hour24 = start.getHour();
        int minute = start.getMinute();
        String ampm = hour24 >= 12 ? "PM" : "AM";
        int hour12 = hour24 % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        return String.format("%s %d:%02d %s", dayName, hour12, minute, ampm);
    }

    // ── SMS copy (deterministic; no model call) ───────────────────────────────────

    /** "I've got Sat 2:00 PM or Sat 4:00 PM — reply 1 or 2." */
    static String buildOfferSms(List<OfferedShowingSlot> slots) {
        StringBuilder body = new StringBuilder("I've got ");
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                body.append(i == slots.size() - 1 ? " or " : ", ");
            }
            body.append(slots.get(i).getLabel());
        }
        body.append(" — reply ");
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                body.append(i == slots.size() - 1 ? " or " : ", ");
            }
            body.append(slots.get(i).getOrdinal());
        }
        body.append('.');
        return body.toString();
    }

    /** "Booked! Sat 2:00 PM. Your agent will meet you there." */
    static String buildConfirmationSms(OfferedShowingSlot slot) {
        return "Booked! " + slot.getLabel() + ". Your agent will meet you there.";
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void emitShowingBooked(UUID tenantId, ConciergeConversation conv, Listing listing,
                                   Meeting meeting, Contact contact, OfferedShowingSlot slot) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", conv.getId() == null ? null : conv.getId().toString());
        if (listing != null && listing.getId() != null) {
            payload.put("listingId", listing.getId().toString());
        }
        payload.put("meetingId", meeting.getId().toString());
        if (contact != null && contact.getId() != null) {
            payload.put("contactId", contact.getId().toString());
        }
        if (slot.getStart() != null) {
            payload.put("start", slot.getStart().toString());
        }
        events.publish(DomainEvent.of(DomainEventType.SHOWING_BOOKED, tenantId, meeting.getId(), payload));
    }

    /** Reply to the buyer; best-effort (an SMS failure is logged, never propagated). */
    private Mono<Void> reply(String to, String bodyText) {
        return twilioSmsService.sendSms(
                        com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest.builder()
                                .to(com.kumouri.kmodigipresbe.model.contact.PhoneContact.builder()
                                        .e164(to).build())
                                .body(bodyText)
                                .build())
                .doOnError(err -> log.warn("RE-3 showing: reply SMS to {} failed: {}", to, err.toString()))
                .onErrorReturn(false)
                .then();
    }
}
