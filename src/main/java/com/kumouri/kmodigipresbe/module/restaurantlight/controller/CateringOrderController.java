package com.kumouri.kmodigipresbe.module.restaurantlight.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.restaurantlight.RestaurantLightAutoConfiguration;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.CateringOrder;
import com.kumouri.kmodigipresbe.module.restaurantlight.service.CateringOrderService;
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
@RequestMapping("/restaurant-light/catering-orders")
@ConditionalOnProperty(prefix = "kmosf.modules.restaurant-light", name = "enabled")
@RequiredArgsConstructor
public class CateringOrderController {

    private final CateringOrderService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<CateringOrder> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<CateringOrder> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CateringOrder> create(@RequestBody CateringOrder body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<CateringOrder> update(@PathVariable UUID id, @RequestBody CateringOrder body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    @PostMapping("/{id}/issue-quote")
    public Mono<CateringOrder> issueQuote(@PathVariable UUID id) {
        return guard().then(service.issueQuote(id));
    }

    @PostMapping("/{id}/confirm")
    public Mono<CateringOrder> confirm(@PathVariable UUID id) {
        return guard().then(service.confirm(id));
    }

    @PostMapping("/{id}/complete")
    public Mono<CateringOrder> complete(@PathVariable UUID id) {
        return guard().then(service.complete(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(RestaurantLightAutoConfiguration.MODULE_KEY);
    }
}
