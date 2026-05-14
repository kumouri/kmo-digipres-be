package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.service.MaintenanceVisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/home-services/maintenance-visits")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@ConditionalOnBean(MaintenanceVisitService.class)
@RequiredArgsConstructor
public class MaintenanceVisitController {

    private final MaintenanceVisitService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<MaintenanceVisit> list(
            @RequestParam(required = false) UUID serviceAgreementId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        if (serviceAgreementId != null) {
            return guard().thenMany(service.findByAgreement(serviceAgreementId));
        }
        if (from != null && to != null) {
            return guard().thenMany(service.findInRange(from, to));
        }
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<MaintenanceVisit> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping("/{id}/dispatch")
    public Mono<MaintenanceVisit> dispatch(@PathVariable UUID id) {
        return guard().then(service.dispatch(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
