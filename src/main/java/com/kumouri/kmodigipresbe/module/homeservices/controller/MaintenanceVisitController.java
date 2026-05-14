package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 10a stub: handlers return empty results. 10b wires {@code MaintenanceVisitService}
 * with {@code dispatch(id)} (creates a {@code WorkOrder}) and standard CRUD.
 */
@RestController
@RequestMapping("/home-services/maintenance-visits")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class MaintenanceVisitController {

    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<MaintenanceVisit> list() {
        return guard().thenMany(Flux.empty());
    }

    @GetMapping("/{id}")
    public Mono<MaintenanceVisit> get(@PathVariable UUID id) {
        return guard().then(Mono.empty());
    }

    @PostMapping("/{id}/dispatch")
    public Mono<MaintenanceVisit> dispatch(@PathVariable UUID id) {
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
