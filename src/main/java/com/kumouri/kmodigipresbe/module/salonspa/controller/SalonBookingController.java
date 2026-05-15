package com.kumouri.kmodigipresbe.module.salonspa.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/salon-spa/bookings")
@ConditionalOnProperty(prefix = "kmosf.modules.salon-spa", name = "enabled")
@RequiredArgsConstructor
public class SalonBookingController {

    private final SalonBookingService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<Booking> list(@RequestParam(required = false) UUID contactId) {
        return guard().thenMany(
                contactId != null ? service.findByContact(contactId) : service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<Booking> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Booking> create(@RequestBody Booking body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<Booking> update(@PathVariable UUID id, @RequestBody Booking body) {
        return guard().then(service.update(id, body));
    }

    @PostMapping("/{id}/confirm")
    public Mono<Booking> confirm(@PathVariable UUID id) {
        return guard().then(service.confirm(id));
    }

    @PostMapping("/{id}/complete")
    public Mono<Booking> complete(@PathVariable UUID id) {
        return guard().then(service.complete(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> cancel(@PathVariable UUID id) {
        return guard().then(service.cancel(id)).then();
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(SalonSpaAutoConfiguration.MODULE_KEY);
    }
}
