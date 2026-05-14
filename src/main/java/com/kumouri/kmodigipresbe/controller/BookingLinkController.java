package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.calendar.BookingLink;
import com.kumouri.kmodigipresbe.service.calendar.BookingLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/booking-links")
@RequiredArgsConstructor
public class BookingLinkController {

    private final BookingLinkService service;

    @GetMapping
    public Flux<BookingLink> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<BookingLink> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BookingLink> create(@RequestBody BookingLink body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<BookingLink> update(@PathVariable UUID id, @RequestBody BookingLink body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
