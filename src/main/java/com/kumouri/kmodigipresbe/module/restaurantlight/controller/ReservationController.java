package com.kumouri.kmodigipresbe.module.restaurantlight.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.restaurantlight.RestaurantLightAutoConfiguration;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.Reservation;
import com.kumouri.kmodigipresbe.module.restaurantlight.service.ReservationService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/restaurant-light/reservations")
@ConditionalOnProperty(prefix = "kmosf.modules.restaurant-light", name = "enabled")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<Reservation> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<Reservation> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Reservation> create(@RequestBody Reservation body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<Reservation> update(@PathVariable UUID id, @RequestBody Reservation body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    @PostMapping("/{id}/confirm")
    public Mono<Reservation> confirm(@PathVariable UUID id) {
        return guard().then(service.confirm(id));
    }

    @PostMapping("/{id}/seat")
    public Mono<Reservation> seat(@PathVariable UUID id) {
        return guard().then(service.seat(id));
    }

    @PostMapping("/{id}/complete")
    public Mono<Reservation> complete(@PathVariable UUID id) {
        return guard().then(service.complete(id));
    }

    @PostMapping("/{id}/no-show")
    public Mono<Reservation> noShow(@PathVariable UUID id) {
        return guard().then(service.markNoShow(id));
    }

    @PostMapping("/{id}/cancel")
    public Mono<Reservation> cancel(@PathVariable UUID id) {
        return guard().then(service.cancel(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(RestaurantLightAutoConfiguration.MODULE_KEY);
    }
}
