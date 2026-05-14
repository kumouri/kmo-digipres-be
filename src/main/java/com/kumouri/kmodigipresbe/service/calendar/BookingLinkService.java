package com.kumouri.kmodigipresbe.service.calendar;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.calendar.BookingLink;
import com.kumouri.kmodigipresbe.repository.BookingLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BookingLinkService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{3,60}$");

    private final BookingLinkRepository links;

    public Flux<BookingLink> findAll() {
        return links.findAll();
    }

    public Mono<BookingLink> findById(UUID id) {
        return links.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "BookingLink not found", 1700, 404)));
    }

    public Mono<BookingLink> create(BookingLink toCreate) {
        if (toCreate.getSlug() == null || !SLUG.matcher(toCreate.getSlug()).matches()) {
            return Mono.error(new DigiPresBeException(
                    "slug must match ^[a-z0-9-]{3,60}$", 1703, 400));
        }
        toCreate.setId(null);
        return links.save(toCreate);
    }

    public Mono<BookingLink> update(UUID id, BookingLink patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getOwnerUserId() != null) existing.setOwnerUserId(patch.getOwnerUserId());
            if (patch.getDurationMinutes() > 0) existing.setDurationMinutes(patch.getDurationMinutes());
            existing.setBufferMinutes(patch.getBufferMinutes());
            existing.setLeadTimeMinutes(patch.getLeadTimeMinutes());
            if (patch.getTimezone() != null) existing.setTimezone(patch.getTimezone());
            if (patch.getAvailability() != null) existing.setAvailability(patch.getAvailability());
            existing.setActive(patch.isActive());
            return links.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return links.deleteById(id);
    }
}
