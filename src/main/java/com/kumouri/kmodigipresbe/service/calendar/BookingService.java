package com.kumouri.kmodigipresbe.service.calendar;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.calendar.AvailabilityRule;
import com.kumouri.kmodigipresbe.model.calendar.BookingLink;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.model.request.BookSlotRequest;
import com.kumouri.kmodigipresbe.model.request.BookingPublicView;
import com.kumouri.kmodigipresbe.repository.BookingLinkRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.MeetingRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public-side booking flow. Resolves a tenant from the {@link BookingLink#getSlug()},
 * generates available slots inside a window, and on book — anonymously creates a
 * Contact (or matches an existing one by email) and a {@link Meeting}. Tenant context
 * for the writes is established explicitly via {@code .contextWrite(...)} because
 * the request is unauthenticated.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingLinkRepository links;
    private final MeetingRepository meetings;
    private final ContactRepository contacts;

    public Mono<BookingPublicView> publicView(String slug, Instant from, Instant to) {
        return resolveActive(slug).flatMap(link ->
                computeSlots(link, from, to).map(slots -> new BookingPublicView(
                        link.getSlug(),
                        link.getTitle(),
                        link.getDescription(),
                        link.getDurationMinutes(),
                        link.getTimezone(),
                        slots)));
    }

    public Mono<Meeting> book(String slug, BookSlotRequest req) {
        return resolveActive(slug).flatMap(link -> {
            Instant slotEnd = req.slotStart().plus(Duration.ofMinutes(link.getDurationMinutes()));
            if (req.slotStart().isBefore(Instant.now().plusSeconds(60L * link.getLeadTimeMinutes()))) {
                return Mono.error(new DigiPresBeException(
                        "Slot is inside the lead-time window", 1701, 409));
            }
            TenantContext anon = new TenantContext(link.getTenantId(), null, Set.of("ANONYMOUS_BOOKING"));
            return upsertContact(req).zipWith(Mono.just(link))
                    .flatMap(tuple -> persistMeeting(tuple.getT2(), tuple.getT1(), req, slotEnd))
                    .contextWrite(TenantContextHolder.write(anon));
        });
    }

    private Mono<BookingLink> resolveActive(String slug) {
        return links.findBySlug(slug)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "BookingLink not found", 1700, 404)))
                .flatMap(link -> link.isActive()
                        ? Mono.just(link)
                        : Mono.error(new DigiPresBeException(
                                "BookingLink is not active", 1702, 410)));
    }

    private Mono<List<Instant>> computeSlots(BookingLink link, Instant from, Instant to) {
        if (link.getAvailability() == null || link.getAvailability().isEmpty()) {
            return Mono.just(List.of());
        }
        ZoneId zone = ZoneId.of(link.getTimezone());
        Instant horizonStart = from.isBefore(Instant.now()) ? Instant.now() : from;
        Instant leadCutoff = Instant.now().plusSeconds(60L * link.getLeadTimeMinutes());
        Instant lower = horizonStart.isBefore(leadCutoff) ? leadCutoff : horizonStart;

        List<Instant> candidates = new ArrayList<>();
        LocalDate day = lower.atZone(zone).toLocalDate();
        LocalDate stop = to.atZone(zone).toLocalDate().plusDays(1);
        Duration step = Duration.ofMinutes((long) link.getDurationMinutes() + link.getBufferMinutes());
        while (day.isBefore(stop)) {
            for (AvailabilityRule rule : link.getAvailability()) {
                if (rule.getDayOfWeek() != day.getDayOfWeek()) continue;
                ZonedDateTime windowStart = ZonedDateTime.of(day, rule.getStartTime(), zone);
                ZonedDateTime windowEnd = ZonedDateTime.of(day, rule.getEndTime(), zone);
                ZonedDateTime cur = windowStart;
                while (!cur.plusMinutes(link.getDurationMinutes()).isAfter(windowEnd)) {
                    Instant slot = cur.toInstant();
                    if (!slot.isBefore(lower) && slot.isBefore(to)) {
                        candidates.add(slot);
                    }
                    cur = cur.plus(step);
                }
            }
            day = day.plusDays(1);
        }

        TenantContext anon = new TenantContext(link.getTenantId(), null, Set.of("ANONYMOUS_BOOKING"));
        return Flux.fromIterable(candidates)
                .filterWhen(slot -> hasNoConflict(slot, link.getDurationMinutes())
                        .contextWrite(TenantContextHolder.write(anon)))
                .collectList();
    }

    private Mono<Boolean> hasNoConflict(Instant slotStart, int durationMinutes) {
        java.time.LocalDateTime startLdt = slotStart.atZone(ZoneId.of("UTC")).toLocalDateTime();
        java.time.LocalDateTime endLdt = slotStart.plus(Duration.ofMinutes(durationMinutes))
                .atZone(ZoneId.of("UTC")).toLocalDateTime();
        // A simple overlap check: any meeting whose [start, end) overlaps [slotStart, slotEnd).
        return meetings.findAll()
                .filter(m -> {
                    if (m.getStart() == null || m.getEnd() == null) return false;
                    return m.getStart().isBefore(endLdt) && m.getEnd().isAfter(startLdt);
                })
                .hasElements()
                .map(any -> !any);
    }

    private Mono<Contact> upsertContact(BookSlotRequest req) {
        return TenantContextHolder.required().flatMap(ctx ->
                contacts.findByTenantAndEmailAddress(ctx.tenantId(), req.attendeeEmail())
                        .next()
                        .switchIfEmpty(Mono.defer(() -> {
                            String[] parts = req.attendeeName().trim().split("\\s+", 2);
                            Contact c = Contact.builder()
                                    .type(ContactType.PERSON)
                                    .firstName(parts.length > 0 ? parts[0] : null)
                                    .lastName(parts.length > 1 ? parts[1] : null)
                                    .displayName(req.attendeeName())
                                    .emails(List.of(new EmailContact(req.attendeeEmail())))
                                    .tags(Set.of("public-booking"))
                                    .build();
                            return contacts.save(c);
                        })));
    }

    private Mono<Meeting> persistMeeting(BookingLink link, Contact attendee,
                                         BookSlotRequest req, Instant slotEnd) {
        ZoneId zone = ZoneId.of(link.getTimezone());
        java.time.LocalDateTime startLdt = req.slotStart().atZone(zone).toLocalDateTime();
        java.time.LocalDateTime endLdt = slotEnd.atZone(zone).toLocalDateTime();
        Meeting m = Meeting.builder()
                .id(UUID.randomUUID())
                .name(link.getTitle() == null ? "Booking" : link.getTitle())
                .description(req.notes())
                .start(startLdt)
                .end(endLdt)
                .attendeeContactIds(Set.of(attendee.getId()))
                .build();
        return meetings.save(m);
    }
}
