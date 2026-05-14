package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
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

/**
 * 10a stub: handlers return empty results. 10c (Equipment + Dispatch board) wires
 * the real {@code EquipmentService} and fills in the warranty scan + JobSite lookup.
 */
@RestController
@RequestMapping("/home-services/equipment")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class EquipmentController {

    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<Equipment> list() {
        return guard().thenMany(Flux.empty());
    }

    @GetMapping("/{id}")
    public Mono<Equipment> get(@PathVariable UUID id) {
        return guard().then(Mono.empty());
    }

    @GetMapping("/by-job-site/{jobSiteId}")
    public Flux<Equipment> byJobSite(@PathVariable UUID jobSiteId) {
        return guard().thenMany(Flux.empty());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Equipment> create(@RequestBody Equipment body) {
        return guard().then(Mono.empty());
    }

    @PutMapping("/{id}")
    public Mono<Equipment> update(@PathVariable UUID id, @RequestBody Equipment body) {
        return guard().then(Mono.empty());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(Mono.empty());
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
