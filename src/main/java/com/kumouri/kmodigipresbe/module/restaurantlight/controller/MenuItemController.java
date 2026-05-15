package com.kumouri.kmodigipresbe.module.restaurantlight.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.restaurantlight.RestaurantLightAutoConfiguration;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.MenuItem;
import com.kumouri.kmodigipresbe.module.restaurantlight.service.MenuItemService;
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
@RequestMapping("/restaurant-light/menu-items")
@ConditionalOnProperty(prefix = "kmosf.modules.restaurant-light", name = "enabled")
@RequiredArgsConstructor
public class MenuItemController {

    private final MenuItemService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<MenuItem> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<MenuItem> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<MenuItem> create(@RequestBody MenuItem body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<MenuItem> update(@PathVariable UUID id, @RequestBody MenuItem body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(RestaurantLightAutoConfiguration.MODULE_KEY);
    }
}
