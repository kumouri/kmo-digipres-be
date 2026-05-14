package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.model.request.BookSlotRequest;
import com.kumouri.kmodigipresbe.model.request.BookingPublicView;
import com.kumouri.kmodigipresbe.service.calendar.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/public/booking")
@RequiredArgsConstructor
public class PublicBookingController {

    private final BookingService booking;

    @GetMapping("/{slug}")
    public Mono<BookingPublicView> view(@PathVariable String slug,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to) {
        return booking.publicView(slug, from, to);
    }

    @PostMapping("/{slug}/book")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Meeting> book(@PathVariable String slug, @Valid @RequestBody BookSlotRequest req) {
        return booking.book(slug, req);
    }
}
